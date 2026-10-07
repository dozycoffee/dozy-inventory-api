package com.dozycoffee.inventory.inventory.application.service

import com.dozycoffee.inventory.global.error.DomainException
import com.dozycoffee.inventory.inventory.application.port.`in`.AllocateInventoryUseCase
import com.dozycoffee.inventory.inventory.application.port.`in`.command.AllocateInventoryCommand
import com.dozycoffee.inventory.inventory.application.port.`in`.result.AllocationResult
import com.dozycoffee.inventory.inventory.application.port.out.AllocationCandidate
import com.dozycoffee.inventory.inventory.application.port.out.InventoryRepository
import com.dozycoffee.inventory.inventory.domain.exception.AllocationConflictException
import com.dozycoffee.inventory.inventory.domain.exception.AllocationHeldException
import com.dozycoffee.inventory.inventory.domain.exception.InsufficientAvailableQuantityException
import com.dozycoffee.inventory.inventory.domain.exception.InventoryNotReservableException
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate

/**
 * 유통기한이 이른 Lot부터 예약 수량을 잡는다(ADR-0022).
 *
 * 후보 조회에는 락이 없으므로 먼저 계획을 세우고(어느 행에서 몇 개) 조건부 UPDATE로 확정한다.
 * 계획을 세운 뒤 확정하기 전에 다른 요청이 같은 행을 가져가면 UPDATE가 실패하고 [AllocationConflictException]을 던진다.
 *
 * 이 서비스는 한 번만 시도한다. [AllocationConflictException]이 나면 호출한 쪽이 트랜잭션을 롤백하고
 * 새 트랜잭션에서 이 UseCase를 다시 호출해야 한다. 같은 트랜잭션 안에서 후보를 다시 조회해도 MySQL REPEATABLE READ가
 * 처음의 스냅샷을 그대로 읽어 똑같이 틀린 계획이 나오기 때문이다. 롤백하면 이미 잡은 수량도 함께 되돌아간다.
 *
 * 여러 행은 상품과 무관하게 `inventory_id` 오름차순으로 갱신해 데드락을 피한다.
 * 호출한 서비스의 트랜잭션 안에서 실행하며 트랜잭션을 직접 열지 않는다.
 */
@Service
class AllocationService(
    private val inventoryRepository: InventoryRepository,
    private val clock: Clock,
) : AllocateInventoryUseCase {
    override suspend fun allocate(command: AllocateInventoryCommand): AllocationResult = apply(command, plan(command))

    /** 후보를 상품별로 할당 순서대로 채워 계획을 세운다. 한 상품이라도 모자라면 가용 수량 부족이다 */
    private suspend fun plan(command: AllocateInventoryCommand): List<Pair<AllocationCandidate, Int>> {
        val candidates: Map<Long, List<AllocationCandidate>> =
            inventoryRepository
                .findAllocationCandidates(command.warehouseId, command.items.map { it.productId }.toSet(), LocalDate.now(clock))
                .groupBy(AllocationCandidate::productId)
        return command.items.flatMap { item: AllocateInventoryCommand.Item ->
            var remaining: Int = item.quantity
            val planned: MutableList<Pair<AllocationCandidate, Int>> = mutableListOf()
            for (candidate: AllocationCandidate in candidates[item.productId].orEmpty()) {
                if (remaining == 0) break
                val take: Int = minOf(remaining, candidate.availableQuantity)
                planned += candidate to take
                remaining -= take
            }
            if (remaining > 0) throw InsufficientAvailableQuantityException()
            planned
        }
    }

    /** 계획을 `inventory_id` 오름차순으로 확정한다. 후보를 잃은 경우는 경합 패배로 본다 */
    private suspend fun apply(
        command: AllocateInventoryCommand,
        plan: List<Pair<AllocationCandidate, Int>>,
    ): AllocationResult {
        for ((candidate: AllocationCandidate, quantity: Int) in plan.sortedBy { it.first.inventoryId }) {
            try {
                inventoryRepository.reserve(candidate.inventoryId, quantity)
            } catch (e: DomainException) {
                if (e.isRaceLoss()) throw AllocationConflictException()
                throw e
            }
        }
        val byProduct: Map<Long, List<Pair<AllocationCandidate, Int>>> = plan.groupBy { it.first.productId }
        return AllocationResult(
            command.items.map { item: AllocateInventoryCommand.Item ->
                AllocationResult.ItemAllocation(
                    item.productId,
                    byProduct.getValue(item.productId).map { (candidate: AllocationCandidate, quantity: Int) ->
                        AllocationResult.LotAllocation(candidate.inventoryId, candidate.lotId, candidate.expirationDate, quantity)
                    },
                )
            },
        )
    }

    private fun DomainException.isRaceLoss(): Boolean =
        this is InsufficientAvailableQuantityException || this is InventoryNotReservableException || this is AllocationHeldException
}
