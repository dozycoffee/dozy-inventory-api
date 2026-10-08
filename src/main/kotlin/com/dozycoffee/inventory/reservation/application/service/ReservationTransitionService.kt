package com.dozycoffee.inventory.reservation.application.service

import com.dozycoffee.inventory.inventory.application.port.`in`.ReleaseInventoryUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.command.ReleaseInventoryCommand
import com.dozycoffee.inventory.reservation.application.port.`in`.ConfirmReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.ExpireReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.ExtendReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.ReleaseReservationUseCase
import com.dozycoffee.inventory.reservation.application.port.`in`.command.ExtendReservationCommand
import com.dozycoffee.inventory.reservation.application.port.`in`.result.ReservationResult
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
import java.time.Clock
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * 예약의 확정·해제·연장·만료(ADR-0023). 예약 상태 변경, 수량 복원, 이력, 이벤트는 한 트랜잭션이다.
 * 확정·해제·연장은 이미 목표 상태이면 바꾸지 않고 현재 상태를 돌려주는 상태 기반 멱등이고(멱등 키 없음),
 * 상태는 `WHERE status = 읽은 상태` 조건부 UPDATE로 바꾼다. 읽은 뒤 다른 요청이 먼저 바꿨으면 트랜잭션을 새로 열어
 * 다시 읽어 판정한다(같은 트랜잭션 안의 재조회는 같은 스냅샷을 읽는다).
 */
@Service
class ReservationTransitionService(
    private val reservationRepository: ReservationRepository,
    private val reservationEventRepository: ReservationEventRepository,
    private val reservationChangedEventPublisher: ReservationChangedEventPublisher,
    private val releaseInventoryUseCase: ReleaseInventoryUseCase,
    private val transactionalOperator: TransactionalOperator,
    private val clock: Clock,
) : ConfirmReservationUseCase,
    ReleaseReservationUseCase,
    ExtendReservationUseCase,
    ExpireReservationUseCase {
    override suspend fun confirm(reservationId: Long): ReservationResult =
        inTransaction {
            val reservation: Reservation = load(reservationId)
            val expected: ReservationStatus = reservation.status
            val now: LocalDateTime = now()
            if (reservation.confirm(now)) {
                persist(
                    reservation,
                    expected,
                    ReservationEventType.CONFIRMED,
                    ReservationChangeType.CONFIRMED,
                    """{"confirmedAt":"$now"}""",
                    false,
                )
            }
            ReservationResult.from(reservation)
        }

    override suspend fun release(reservationId: Long): ReservationResult =
        inTransaction {
            val reservation: Reservation = load(reservationId)
            val expected: ReservationStatus = reservation.status
            if (reservation.release()) {
                persist(
                    reservation,
                    expected,
                    ReservationEventType.RELEASED,
                    ReservationChangeType.RELEASED,
                    """{"previousStatus":"$expected"}""",
                    true,
                )
            }
            ReservationResult.from(reservation)
        }

    override suspend fun extend(command: ExtendReservationCommand): ReservationResult =
        inTransaction {
            val reservation: Reservation = load(command.reservationId)
            val expected: ReservationStatus = reservation.status
            val previousExpiresAt: LocalDateTime? = reservation.expiry.expiresAt
            val newExpiresAt: LocalDateTime = command.expiresAt.truncatedTo(ChronoUnit.MICROS)
            if (reservation.extend(newExpiresAt, now())) {
                val detail: String = """{"previousExpiresAt":"$previousExpiresAt","expiresAt":"$newExpiresAt"}"""
                persist(reservation, expected, ReservationEventType.EXTENDED, ReservationChangeType.EXTENDED, detail, false)
            }
            ReservationResult.from(reservation)
        }

    override suspend fun expire(reservationId: Long): Boolean =
        checkNotNull(
            transactionalOperator.executeAndAwait {
                val reservation: Reservation = reservationRepository.findById(reservationId) ?: return@executeAndAwait false
                val expiresAt: LocalDateTime? = reservation.expiry.expiresAt
                // 확정·해제와 겹쳐 조건부 UPDATE에서 밀리면 이미 처리된 것이라 만료할 것이 없다
                if (!reservation.expire(now()) || !reservationRepository.updateState(reservation, ReservationStatus.RESERVED)) {
                    return@executeAndAwait false
                }
                afterStateChange(
                    reservation,
                    ReservationEventType.EXPIRED,
                    ReservationChangeType.EXPIRED,
                    """{"expiresAt":"$expiresAt"}""",
                    true,
                )
                true
            },
        ) { "만료 결과가 없다" }

    /**
     * 읽고 → 전이를 적용하고 → 조건부로 저장하는 흐름을 한 트랜잭션으로 실행한다. 읽은 뒤 다른 요청이 먼저 상태를 바꿔 저장이 밀리면
     * 트랜잭션을 새로 열어 바뀐 상태를 다시 읽고 판정한다. 이미 목표 상태이면 저장하지 않고 현재 상태를 돌려준다(멱등).
     */
    private suspend fun inTransaction(block: suspend () -> ReservationResult): ReservationResult {
        repeat(MAX_ATTEMPTS) {
            try {
                return checkNotNull(transactionalOperator.executeAndAwait { block() }) { "예약 전이 결과가 없다" }
            } catch (e: ReservationChangedConcurrentlyException) {
                // 새 트랜잭션에서 바뀐 상태를 다시 읽어 판정한다
            }
        }
        throw ReservationChangedConcurrentlyException()
    }

    private suspend fun load(reservationId: Long): Reservation =
        reservationRepository.findById(reservationId) ?: throw ReservationNotFoundException()

    /** 읽었을 때의 상태가 [expected]일 때만 저장하고, 밀렸으면 [ReservationChangedConcurrentlyException]이다 */
    private suspend fun persist(
        reservation: Reservation,
        expected: ReservationStatus,
        eventType: ReservationEventType,
        changeType: ReservationChangeType,
        detail: String,
        returnsQuantity: Boolean,
    ) {
        if (!reservationRepository.updateState(reservation, expected)) throw ReservationChangedConcurrentlyException()
        afterStateChange(reservation, eventType, changeType, detail, returnsQuantity)
    }

    /** 상태를 바꾼 뒤의 후속 처리: 예약 수량 복원, 이력, 이벤트 */
    private suspend fun afterStateChange(
        reservation: Reservation,
        eventType: ReservationEventType,
        changeType: ReservationChangeType,
        detail: String,
        returnsQuantity: Boolean,
    ) {
        if (returnsQuantity) releaseQuantity(reservation)
        reservationEventRepository.save(
            ReservationEvent.create(checkNotNull(reservation.reservationId) { "저장된 예약은 식별자가 있어야 한다" }, eventType, detail),
        )
        reservationChangedEventPublisher.publish(ReservationChangedEvent.of(changeType, reservation))
    }

    /** 출고되지 않고 남은 예약 수량(할당 수량 − 출고 확정 수량)을 재고 행별로 되돌린다 */
    private suspend fun releaseQuantity(reservation: Reservation) {
        val remaining: Map<Long, Int> =
            reservation.items
                .flatMap(ReservationItem::allocations)
                .groupBy(ReservationAllocation::inventoryId) { it.quantity - it.fulfilledQuantity }
                .mapValues { (_, quantities: List<Int>) -> quantities.sum() }
                .filterValues { it > 0 }
        if (remaining.isEmpty()) return
        releaseInventoryUseCase.release(
            ReleaseInventoryCommand(
                remaining.map { (inventoryId: Long, quantity: Int) ->
                    ReleaseInventoryCommand.Item(inventoryId, quantity)
                },
            ),
        )
    }

    /** 시각 컬럼이 마이크로초(DATETIME(6))라 먼저 잘라 둔다 */
    private fun now(): LocalDateTime = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS)
}

private const val MAX_ATTEMPTS: Int = 3
