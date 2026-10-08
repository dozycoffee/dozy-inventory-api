package com.dozycoffee.inventory.reservation.application.service

import com.dozycoffee.inventory.global.domain.IdempotencyKey
import com.dozycoffee.inventory.global.domain.RequesterService
import com.dozycoffee.inventory.global.error.IdempotencyKeyConflictException
import com.dozycoffee.inventory.inventory.application.port.`in`.GetOutboundResultUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.ShipInventoryUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.command.ShipInventoryCommand
import com.dozycoffee.inventory.inventory.application.port.`in`.result.ShipResult
import com.dozycoffee.inventory.reservation.application.port.`in`.FulfillReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.command.FulfillReservationCommand
import com.dozycoffee.inventory.reservation.application.port.`in`.result.FulfillmentResult
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
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait

/**
 * 예약의 출고 확정(ADR-0024). 예약 상태·출고 수량 저장, 재고 반영(총 수량·예약 수량 차감, 결품은 예약 수량만 복원, `OUTBOUND` 이력),
 * 예약 이력, 이벤트는 한 트랜잭션이다. 저장은 `CONFIRMED` → `FULFILLED` 조건부 UPDATE라 읽은 뒤 다른 요청이 먼저 상태를 바꿨으면
 * 트랜잭션을 새로 열어 다시 읽어 판정한다(최대 [MAX_ATTEMPTS]회). 이미 출고 확정된 예약은 같은 멱등 키와 같은 수량이면 새로 반영하지 않고
 * 처음과 같은 결과를 돌려주며, 처리 후 수량은 키로 `OUTBOUND` 이력을 다시 조회해 만든다.
 */
@Service
class ReservationFulfillmentService(
    private val reservationRepository: ReservationRepository,
    private val reservationEventRepository: ReservationEventRepository,
    private val reservationChangedEventPublisher: ReservationChangedEventPublisher,
    private val shipInventoryUseCase: ShipInventoryUseCase,
    private val getOutboundResultUseCase: GetOutboundResultUseCase,
    private val transactionalOperator: TransactionalOperator,
) : FulfillReservationUseCase {
    override suspend fun fulfill(command: FulfillReservationCommand): FulfillmentResult {
        // 쓰기 전에 형식을 검증한다
        val key: IdempotencyKey = IdempotencyKey.of(command.idempotencyKey)
        val requester: RequesterService = RequesterService.of(command.requesterService)
        val shipments: Map<Long, Int> = command.allocations.associate { it.inventoryId to it.shippedQuantity }

        repeat(MAX_ATTEMPTS) {
            try {
                return checkNotNull(
                    transactionalOperator.executeAndAwait { fulfillOnce(command.reservationId, shipments, key, requester) },
                ) { "출고 확정 결과가 없다" }
            } catch (e: ReservationChangedConcurrentlyException) {
                // 새 트랜잭션에서 바뀐 상태를 다시 읽어 판정한다
            }
        }
        throw ReservationChangedConcurrentlyException()
    }

    private suspend fun fulfillOnce(
        reservationId: Long,
        shipments: Map<Long, Int>,
        key: IdempotencyKey,
        requester: RequesterService,
    ): FulfillmentResult {
        val reservation: Reservation = reservationRepository.findById(reservationId) ?: throw ReservationNotFoundException()
        val expected: ReservationStatus = reservation.status
        if (!reservation.fulfill(shipments)) return replay(reservation, key)

        if (!reservationRepository.updateFulfillment(reservation, expected)) throw ReservationChangedConcurrentlyException()
        val shipped: ShipResult =
            shipInventoryUseCase.ship(
                ShipInventoryCommand(
                    items =
                        allocationsOf(reservation).map {
                            ShipInventoryCommand.Item(it.inventoryId, it.fulfilledQuantity, it.shortageQuantity)
                        },
                    idempotencyKey = key.value,
                    referenceId = reservationId,
                    requesterService = requester.value,
                ),
            )
        reservationEventRepository.save(ReservationEvent.create(reservationId, ReservationEventType.FULFILLED, detailOf(reservation)))
        reservationChangedEventPublisher.publish(ReservationChangedEvent.of(ReservationChangeType.FULFILLED, reservation))
        return FulfillmentResult.from(
            reservation,
            shipped.items.mapNotNull { item: ShipResult.Item -> item.quantityAfter?.let { item.inventoryId to it } }.toMap(),
        )
    }

    /**
     * 이미 출고 확정된 예약의 재요청이다. 처리 후 수량은 같은 멱등 키의 이력에서 다시 만들고, 출고한 행의 이력이 없으면
     * 다른 키로 온 요청이므로 거부한다(모든 행의 출고 수량이 0이면 이력이 없어 키를 확인할 수 없고 바뀌는 것도 없다).
     */
    private suspend fun replay(
        reservation: Reservation,
        key: IdempotencyKey,
    ): FulfillmentResult {
        val quantitiesAfter: Map<Long, Int> =
            getOutboundResultUseCase.getQuantitiesAfter(key.value, checkNotNull(reservation.reservationId) { "저장된 예약은 식별자가 있어야 한다" })
        if (allocationsOf(reservation).any {
                it.fulfilledQuantity > 0 && it.inventoryId !in quantitiesAfter
            }
        ) {
            throw IdempotencyKeyConflictException()
        }
        return FulfillmentResult.from(reservation, quantitiesAfter)
    }

    private fun allocationsOf(reservation: Reservation): List<ReservationAllocation> =
        reservation.items.flatMap(ReservationItem::allocations)

    /** 결품 수량을 포함한 재고 행별 출고 내역을 이력의 상세로 남긴다 */
    private fun detailOf(reservation: Reservation): String =
        allocationsOf(reservation).joinToString(separator = ",", prefix = """{"allocations":[""", postfix = "]}") {
            """{"inventoryId":${it.inventoryId},"allocated":${it.quantity},"shipped":${it.fulfilledQuantity},"shortage":${it.shortageQuantity}}"""
        }
}

private const val MAX_ATTEMPTS: Int = 3
