package com.dozycoffee.inventory.reservation.application.service

import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.error.AllocationConflictException
import com.dozycoffee.inventory.global.error.DomainException
import com.dozycoffee.inventory.global.error.IdempotencyKeyConflictException
import com.dozycoffee.inventory.inventory.application.port.`in`.AllocateInventoryUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.GetInventoryLotsUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.HoldInventoryAllocationUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.ReleaseInventoryUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.command.AllocateInventoryCommand
import com.dozycoffee.inventory.inventory.application.port.`in`.command.ReleaseInventoryCommand
import com.dozycoffee.inventory.inventory.application.port.`in`.result.AllocationResult
import com.dozycoffee.inventory.inventory.application.port.`in`.result.InventoryLotResult
import com.dozycoffee.inventory.reservation.application.port.`in`.ReallocateReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.command.ReallocateReservationCommand
import com.dozycoffee.inventory.reservation.application.port.`in`.result.ReallocationResult
import com.dozycoffee.inventory.reservation.application.port.out.ReservationChangeType
import com.dozycoffee.inventory.reservation.application.port.out.ReservationChangedEvent
import com.dozycoffee.inventory.reservation.application.port.out.ReservationChangedEventPublisher
import com.dozycoffee.inventory.reservation.application.port.out.ReservationEventRepository
import com.dozycoffee.inventory.reservation.application.port.out.ReservationRepository
import com.dozycoffee.inventory.reservation.domain.enumeration.ReservationEventType
import com.dozycoffee.inventory.reservation.domain.enumeration.ReservationStatus
import com.dozycoffee.inventory.reservation.domain.exception.ReservationChangedConcurrentlyException
import com.dozycoffee.inventory.reservation.domain.exception.ReservationNotFoundException
import com.dozycoffee.inventory.reservation.domain.model.Reservation
import com.dozycoffee.inventory.reservation.domain.model.ReservationAllocation
import com.dozycoffee.inventory.reservation.domain.model.ReservationEvent
import com.dozycoffee.inventory.reservation.domain.model.ReservationItem
import kotlinx.coroutines.delay
import org.springframework.dao.TransientDataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import kotlin.random.Random

/**
 * 확정된 예약의 Lot 재할당(ADR-0031). 부족이 보고된 재고 행의 할당 보류는 먼저 별도 트랜잭션으로 확정한다. 대체 재고가 모자라 재할당이
 * 실패해도 부족 사실은 남기 위해서이고, 보류된 행은 이어지는 대체 Lot 선택(FEFO)에서 빠진다. 재할당(대체 Lot 예약 수량 확보, 부족 행 예약 수량 반환,
 * 예약 할당 저장, 이력, 이벤트)은 한 트랜잭션이고 예약 행을 먼저 잠근 뒤 재고 행을 갱신한다.
 *
 * 다른 요청에 밀리면([AllocationConflictException], [ReservationChangedConcurrentlyException], 데드락) 트랜잭션을 새로 열어 처음부터 다시 시도한다.
 * 같은 멱등 키는 `reservation_event`의 유니크 제약이 막아 동시에 오면 한쪽이 롤백되고, 다시 시도하면 저장된 이력으로 처음과 같은 결과를 돌려준다.
 */
@Service
class ReservationReallocationService(
    private val reservationRepository: ReservationRepository,
    private val reservationEventRepository: ReservationEventRepository,
    private val reservationChangedEventPublisher: ReservationChangedEventPublisher,
    private val allocateInventoryUseCase: AllocateInventoryUseCase,
    private val releaseInventoryUseCase: ReleaseInventoryUseCase,
    private val holdInventoryAllocationUseCase: HoldInventoryAllocationUseCase,
    private val getInventoryLotsUseCase: GetInventoryLotsUseCase,
    private val transactionalOperator: TransactionalOperator,
) : ReallocateReservationUseCase {
    override suspend fun reallocate(command: ReallocateReservationCommand): ReallocationResult {
        val key: IdempotencyKey = IdempotencyKey.of(command.idempotencyKey)
        val shortages: Map<Long, Int> = command.shortages.associate { it.inventoryId to it.shortageQuantity }

        // 보류하기 전에 이미 처리한 요청과 잘못된 요청을 걸러낸다
        findPreviousResult(command.reservationId, key, shortages)?.let { return it }
        val reservation: Reservation = reservationRepository.findById(command.reservationId) ?: throw ReservationNotFoundException()
        try {
            reservation.reallocationDemand(shortages)
        } catch (e: DomainException) {
            // 같은 키의 요청이 이력을 읽은 뒤 방금 끝나 할당이 바뀐 경우라면 입력 오류가 아니라 재요청이다
            findPreviousResult(command.reservationId, key, shortages)?.let { return it }
            throw e
        }

        transactionalOperator.executeAndAwait {
            holdInventoryAllocationUseCase.hold(shortages.keys, "피킹 불가 보고: 예약 ${command.reservationId}")
        }

        var lastConflict: RuntimeException = ReservationChangedConcurrentlyException()
        repeat(MAX_ATTEMPTS) {
            try {
                return checkNotNull(
                    transactionalOperator.executeAndAwait { reallocateOnce(command.reservationId, key, shortages) },
                ) { "재할당 결과가 없다" }
            } catch (e: AllocationConflictException) {
                lastConflict = e
            } catch (e: ReservationChangedConcurrentlyException) {
                lastConflict = e
            } catch (e: TransientDataAccessException) {
                lastConflict = e
            }
            // 새 트랜잭션에서 새 스냅샷으로 다시 판정한다. 같은 행에 몰린 요청이 다시 같이 부딪히지 않게 무작위로 조금 쉰다
            delay(Random.nextLong(MAX_BACKOFF_MILLIS))
        }
        throw lastConflict
    }

    private suspend fun reallocateOnce(
        reservationId: Long,
        key: IdempotencyKey,
        shortages: Map<Long, Int>,
    ): ReallocationResult {
        findPreviousResult(reservationId, key, shortages)?.let { return it }
        val reservation: Reservation = reservationRepository.findById(reservationId) ?: throw ReservationNotFoundException()
        val demand: Map<Long, Int> = reservation.reallocationDemand(shortages)
        val before: Map<Long, Int> = quantitiesOf(reservation)

        // 예약 행을 먼저 잠근다(확정이 유지되는 동안만 재할당할 수 있다)
        if (!reservationRepository.updateState(reservation, ReservationStatus.CONFIRMED)) throw ReservationChangedConcurrentlyException()
        // 재고 행을 건드리기 전에 읽은 할당이 낡지 않았는지 최신 커밋으로 확인한다(같은 예약의 다른 재할당이 먼저 끝났을 수 있다)
        if (!reservationRepository.lockAllocations(reservation, before)) throw ReservationChangedConcurrentlyException()
        val allocated: AllocationResult =
            allocateInventoryUseCase.allocate(
                AllocateInventoryCommand(
                    reservation.warehouseId,
                    demand.map { (productId: Long, quantity: Int) ->
                        AllocateInventoryCommand.Item(productId, quantity)
                    },
                ),
            )
        releaseInventoryUseCase.release(
            ReleaseInventoryCommand(
                shortages.map { (inventoryId: Long, quantity: Int) ->
                    ReleaseInventoryCommand.Item(inventoryId, quantity)
                },
            ),
        )
        reservation.reallocate(
            shortages,
            allocated.items.associate { item: AllocationResult.ItemAllocation ->
                item.productId to item.lots.map { it.inventoryId to it.quantity }
            },
        )
        if (!reservationRepository.updateAllocations(reservation, before)) throw ReservationChangedConcurrentlyException()

        reservationEventRepository.save(
            ReservationEvent.create(
                reservationId,
                ReservationEventType.REALLOCATED,
                detailOf(shortages, reservation, demand.keys),
                key.value,
            ),
        )
        reservationChangedEventPublisher.publish(ReservationChangedEvent.of(ReservationChangeType.REALLOCATED, reservation))
        return resultOf(reservation, shortages.keys)
    }

    /**
     * 같은 멱등 키로 이미 재할당했으면 예약의 현재 할당으로 결과를 다시 만든다. 부족 내역이 다르면 거부한다.
     * MySQL이 JSON 컬럼의 키 순서와 공백을 바꾸므로 상세에 정렬한 부족 내역 문자열(`재고행:수량,...`)을 따로 넣어 두고 그 값을 비교한다.
     * 이력을 먼저 읽고 예약을 나중에 읽는다. 이력이 보이면 같은 트랜잭션에서 저장된 할당도 이미 반영되어 있어, 예약을 먼저 읽고 이력을 나중에 읽을 때
     * 생기는 낡은 할당 응답이 나오지 않는다(트랜잭션 밖의 읽기는 각각 최신 커밋을 읽는다).
     */
    private suspend fun findPreviousResult(
        reservationId: Long,
        key: IdempotencyKey,
        shortages: Map<Long, Int>,
    ): ReallocationResult? {
        val previous: ReservationEvent = reservationEventRepository.findByIdempotencyKey(reservationId, key.value) ?: return null
        if (previous.detail?.let { SHORTAGES_FIELD.find(it)?.groupValues?.get(1) } !=
            shortagesKey(shortages)
        ) {
            throw IdempotencyKeyConflictException()
        }
        val reservation: Reservation = reservationRepository.findById(reservationId) ?: throw ReservationNotFoundException()
        return resultOf(reservation, shortages.keys)
    }

    private suspend fun resultOf(
        reservation: Reservation,
        heldInventoryIds: Set<Long>,
    ): ReallocationResult {
        val lots: Map<Long, InventoryLotResult> =
            getInventoryLotsUseCase
                .getLots(
                    reservation.items
                        .flatMap { item: ReservationItem ->
                            item.allocations.map(ReservationAllocation::inventoryId)
                        }.toSet(),
                ).associateBy(InventoryLotResult::inventoryId)
        return ReallocationResult.from(reservation, heldInventoryIds, lots)
    }

    /** 재할당 전에 읽은 할당 ID별 수량. 저장할 때 그 사이 다른 요청이 바꾸지 않았는지 확인하는 기준이다 */
    private fun quantitiesOf(reservation: Reservation): Map<Long, Int> =
        reservation.items
            .flatMap(ReservationItem::allocations)
            .associate { checkNotNull(it.reservationAllocationId) { "저장된 할당은 식별자가 있어야 한다" } to it.quantity }

    /** 부족 내역과 재할당 뒤 해당 상품의 할당을 이력의 상세로 남긴다 */
    private fun detailOf(
        shortages: Map<Long, Int>,
        reservation: Reservation,
        productIds: Set<Long>,
    ): String =
        """{"shortages":"${shortagesKey(shortages)}","allocations":[""" +
            reservation.items
                .filter { it.productId in productIds }
                .flatMap { item: ReservationItem ->
                    item.allocations.map { """{"productId":${item.productId},"inventoryId":${it.inventoryId},"quantity":${it.quantity}}""" }
                }.joinToString(separator = ",", postfix = "]}")
}

/** 재고 행 ID 오름차순으로 정렬한 부족 내역 문자열(`재고행:수량,...`). 같은 요청인지 비교하는 기준이다 */
private fun shortagesKey(shortages: Map<Long, Int>): String =
    shortages.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }

private val SHORTAGES_FIELD: Regex = Regex(""""shortages"\s*:\s*"([^"]*)"""")

private const val MAX_ATTEMPTS: Int = 5
private const val MAX_BACKOFF_MILLIS: Long = 30L
