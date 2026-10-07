package com.dozycoffee.inventory.reservation.application.service

import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.domain.RequesterService
import com.dozycoffee.inventory.global.error.AllocationConflictException
import com.dozycoffee.inventory.global.error.IdempotencyKeyConflictException
import com.dozycoffee.inventory.inventory.application.port.`in`.AllocateInventoryUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.command.AllocateInventoryCommand
import com.dozycoffee.inventory.inventory.application.port.`in`.result.AllocationResult
import com.dozycoffee.inventory.product.application.port.`in`.GetProductUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.CreateReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.command.CreateReservationCommand
import com.dozycoffee.inventory.reservation.application.port.`in`.result.ReservationResult
import com.dozycoffee.inventory.reservation.application.port.out.ReservationChangeType
import com.dozycoffee.inventory.reservation.application.port.out.ReservationChangedEvent
import com.dozycoffee.inventory.reservation.application.port.out.ReservationChangedEventPublisher
import com.dozycoffee.inventory.reservation.application.port.out.ReservationEventRepository
import com.dozycoffee.inventory.reservation.application.port.out.ReservationPolicy
import com.dozycoffee.inventory.reservation.application.port.out.ReservationRepository
import com.dozycoffee.inventory.reservation.domain.enumeration.ReservationEventType
import com.dozycoffee.inventory.reservation.domain.exception.DuplicateOrderReservationException
import com.dozycoffee.inventory.reservation.domain.exception.DuplicateReservationKeyException
import com.dozycoffee.inventory.reservation.domain.exception.ProductNotReservableException
import com.dozycoffee.inventory.reservation.domain.model.Reservation
import com.dozycoffee.inventory.reservation.domain.model.ReservationAllocation
import com.dozycoffee.inventory.reservation.domain.model.ReservationEvent
import com.dozycoffee.inventory.reservation.domain.model.ReservationItem
import com.dozycoffee.inventory.reservation.domain.valueobject.ExternalOrderId
import com.dozycoffee.inventory.reservation.domain.valueobject.ReservationChannel
import com.dozycoffee.inventory.reservation.domain.valueobject.ReservationExpiry
import kotlinx.coroutines.delay
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Clock
import java.time.LocalDateTime
import kotlin.random.Random

/**
 * 예약 생성. 가용 수량 할당, 예약 저장, 이력, 이벤트는 한 트랜잭션이고 트랜잭션 밖에서 두 가지 경합을 처리한다(ADR-0022).
 * 할당 중 다른 요청에 밀리면([AllocationConflictException]) 트랜잭션을 새로 열어 처음부터 다시 시도하고(같은 트랜잭션 안의 재조회는
 * 같은 스냅샷을 읽는다), 같은 멱등 키가 동시에 들어와 중복 키나 중복 주문 검사에 걸리면 저장된 예약으로 이전 결과를 반환한다.
 */
@Service
class ReservationService(
    private val getProductUseCase: GetProductUseCase,
    private val allocateInventoryUseCase: AllocateInventoryUseCase,
    private val reservationRepository: ReservationRepository,
    private val reservationEventRepository: ReservationEventRepository,
    private val reservationChangedEventPublisher: ReservationChangedEventPublisher,
    private val transactionalOperator: TransactionalOperator,
    private val clock: Clock,
    private val reservationPolicy: ReservationPolicy,
) : CreateReservationUseCase {
    override suspend fun create(command: CreateReservationCommand): ReservationResult {
        // 쓰기 전에 요청 형식과 만료 시각을 모두 검증한다. 만든 값은 재시도에서도 그대로 쓴다
        val key: IdempotencyKey = IdempotencyKey.of(command.idempotencyKey)
        val channel: ReservationChannel = ReservationChannel.of(command.channel)
        val externalOrderId: ExternalOrderId = ExternalOrderId.of(command.externalOrderId)
        val requesterService: RequesterService = RequesterService.of(command.requesterService)
        val now: LocalDateTime = LocalDateTime.now(clock)
        val expiry: ReservationExpiry =
            ReservationExpiry.create(command.expiresAt, now.plus(reservationPolicy.maxTtlFor(channel.value)), now)
        val allocation: AllocateInventoryCommand =
            AllocateInventoryCommand(command.warehouseId, command.items.map { AllocateInventoryCommand.Item(it.productId, it.quantity) })
        val newReservation: (List<ReservationItem>) -> Reservation = { items: List<ReservationItem> ->
            Reservation.create(command.warehouseId, channel, externalOrderId, expiry, key, requesterService, items)
        }

        findPreviousResult(command, key)?.let { return it }
        repeat(MAX_ATTEMPTS) {
            try {
                return applyOnce(command, key, allocation, newReservation)
            } catch (e: AllocationConflictException) {
                // 새 트랜잭션에서 새 스냅샷으로 다시 계획한다. 같은 행에 몰린 요청이 다시 같이 부딪히지 않게 무작위로 조금 쉰다
                delay(Random.nextLong(MAX_BACKOFF_MILLIS))
            }
        }
        throw AllocationConflictException()
    }

    private suspend fun applyOnce(
        command: CreateReservationCommand,
        key: IdempotencyKey,
        allocation: AllocateInventoryCommand,
        newReservation: (List<ReservationItem>) -> Reservation,
    ): ReservationResult =
        try {
            checkNotNull(transactionalOperator.executeAndAwait { reserve(command, key, allocation, newReservation) }) { "예약 결과가 없다" }
        } catch (e: DuplicateReservationKeyException) {
            findPreviousResult(command, key) ?: throw e
        } catch (e: DuplicateOrderReservationException) {
            // 같은 키의 먼저 온 요청이 방금 만든 예약에 걸린 것이면 중복 주문이 아니라 재요청이다
            findPreviousResult(command, key) ?: throw e
        }

    private suspend fun reserve(
        command: CreateReservationCommand,
        key: IdempotencyKey,
        allocation: AllocateInventoryCommand,
        newReservation: (List<ReservationItem>) -> Reservation,
    ): ReservationResult {
        command.items.forEach { item: CreateReservationCommand.Item ->
            if (!getProductUseCase.getById(item.productId).isActive) throw ProductNotReservableException()
        }
        val isDuplicateOrder: Boolean =
            reservationRepository.existsActive(
                ReservationChannel.of(command.channel),
                ExternalOrderId.of(command.externalOrderId),
                LocalDateTime.now(clock),
            )
        if (isDuplicateOrder) {
            throw DuplicateOrderReservationException()
        }

        val allocated: AllocationResult = allocateInventoryUseCase.allocate(allocation)
        val quantities: Map<Long, Int> = command.items.associate { it.productId to it.quantity }
        val saved: Reservation =
            reservationRepository.save(
                newReservation(
                    allocated.items.map { item: AllocationResult.ItemAllocation ->
                        ReservationItem.create(
                            item.productId,
                            quantities.getValue(item.productId),
                            item.lots.map { ReservationAllocation.create(it.inventoryId, it.quantity) },
                        )
                    },
                ),
            )
        val reservationId: Long = checkNotNull(saved.reservationId) { "저장된 예약은 식별자가 있어야 한다" }
        reservationEventRepository.save(
            ReservationEvent.create(
                reservationId,
                ReservationEventType.CREATED,
                """{"expiresAt":"${saved.expiry.expiresAt}","maxExpiresAt":"${saved.expiry.maxExpiresAt}"}""",
            ),
        )
        reservationChangedEventPublisher.publish(
            ReservationChangedEvent(
                changeType = ReservationChangeType.CREATED,
                reservationId = reservationId,
                warehouseId = saved.warehouseId,
                channel = saved.channel.value,
                externalOrderId = saved.externalOrderId.value,
                items = quantities.map { (productId: Long, quantity: Int) -> ReservationChangedEvent.Item(productId, quantity) },
                idempotencyKey = key.value,
            ),
        )
        return result(saved)
    }

    /** 같은 멱등 키로 이미 만든 예약이 있으면 그 예약을 반환한다. 요청 내용이 다르면 거부한다. 만료 시각은 시간에 따라 달라 비교하지 않는다 */
    private suspend fun findPreviousResult(
        command: CreateReservationCommand,
        key: IdempotencyKey,
    ): ReservationResult? {
        val previous: Reservation = reservationRepository.findByIdempotencyKey(key) ?: return null
        val sameRequest: Boolean =
            previous.warehouseId == command.warehouseId &&
                previous.channel.value == command.channel &&
                previous.externalOrderId.value == command.externalOrderId &&
                previous.items.associate { it.productId to it.requestedQuantity } == command.items.associate { it.productId to it.quantity }
        if (!sameRequest) throw IdempotencyKeyConflictException()
        return result(previous)
    }

    private fun result(reservation: Reservation): ReservationResult =
        ReservationResult(
            reservationId = checkNotNull(reservation.reservationId) { "저장된 예약은 식별자가 있어야 한다" },
            status = reservation.status,
            warehouseId = reservation.warehouseId,
            channel = reservation.channel.value,
            externalOrderId = reservation.externalOrderId.value,
            expiresAt = reservation.expiry.expiresAt,
            maxExpiresAt = reservation.expiry.maxExpiresAt,
            items =
                reservation.items.map { item: ReservationItem ->
                    ReservationResult.Item(
                        item.productId,
                        item.requestedQuantity,
                        item.allocations.map { ReservationResult.Allocation(it.inventoryId, it.quantity) },
                    )
                },
        )
}

private const val MAX_ATTEMPTS: Int = 5
private const val MAX_BACKOFF_MILLIS: Long = 30L
