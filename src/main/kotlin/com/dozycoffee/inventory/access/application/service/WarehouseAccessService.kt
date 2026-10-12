package com.dozycoffee.inventory.access.application.service

import com.dozycoffee.inventory.access.application.port.`in`.ReplaceWarehouseAccessUseCase
import com.dozycoffee.inventory.access.application.port.`in`.ResolveWarehouseScopeUseCase
import com.dozycoffee.inventory.access.application.port.`in`.command.ReplaceWarehouseAccessCommand
import com.dozycoffee.inventory.access.application.port.out.WarehouseAccessRepository
import com.dozycoffee.inventory.access.domain.model.WarehouseAccess
import com.dozycoffee.inventory.access.domain.valueobject.PrincipalId
import com.dozycoffee.inventory.global.domain.WarehouseScope
import com.dozycoffee.inventory.global.error.WarehouseAccessDeniedException
import com.dozycoffee.inventory.global.security.Actor
import com.dozycoffee.inventory.global.security.InventoryRole
import com.dozycoffee.inventory.global.security.UserActor
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait

/**
 * 직원의 창고 소속 사본을 교체하고(WMS 이벤트) 요청자의 접근 범위를 판정한다(ADR-0032).
 * 범위는 role로 나눈다: `admin`·`service`는 모든 창고, `warehouse_manager`만 가지면 사본의 창고만이다.
 */
@Service
class WarehouseAccessService(
    private val warehouseAccessRepository: WarehouseAccessRepository,
    private val transactionalOperator: TransactionalOperator,
) : ReplaceWarehouseAccessUseCase,
    ResolveWarehouseScopeUseCase {
    override suspend fun replace(command: ReplaceWarehouseAccessCommand) {
        // 쓰기 전에 형식을 검증한다
        val access: WarehouseAccess = WarehouseAccess.of(PrincipalId.of(command.principalId), command.warehouseIds)
        transactionalOperator.executeAndAwait { warehouseAccessRepository.replace(access) }
    }

    override suspend fun resolve(actor: Actor): WarehouseScope {
        if (actor !is UserActor) return WarehouseScope.All
        if (InventoryRole.ADMIN.code in actor.roles || InventoryRole.SERVICE.code in actor.roles) return WarehouseScope.All
        if (InventoryRole.WAREHOUSE_MANAGER.code !in actor.roles) throw WarehouseAccessDeniedException()

        val warehouseIds: Set<Long> = warehouseAccessRepository.findWarehouseIds(PrincipalId.of(actor.principalId))
        if (warehouseIds.isEmpty()) {
            // 동기화 문제일 수 있어 운영 알림이 걸 수 있도록 고정된 키워드로 남긴다
            log.error("WAREHOUSE_ACCESS_COPY_MISSING 창고 관리자의 소속 사본이 없어 접근을 거부한다: principalId={}", actor.principalId)
            throw WarehouseAccessDeniedException()
        }
        return WarehouseScope.Only(warehouseIds)
    }
}

private val log: Logger = LoggerFactory.getLogger(WarehouseAccessService::class.java)
