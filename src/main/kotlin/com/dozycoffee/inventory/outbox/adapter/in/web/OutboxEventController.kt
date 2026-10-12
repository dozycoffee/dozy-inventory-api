package com.dozycoffee.inventory.outbox.adapter.`in`.web

import com.dozycoffee.inventory.global.security.InventoryAuthorize
import com.dozycoffee.inventory.outbox.adapter.`in`.web.response.OutboxEventPageResponse
import com.dozycoffee.inventory.outbox.adapter.`in`.web.response.OutboxEventResponse
import com.dozycoffee.inventory.outbox.application.port.`in`.GetDeadOutboxEventsUseCase
import com.dozycoffee.inventory.outbox.application.port.`in`.RequeueOutboxEventUseCase
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 격리(`DEAD`)된 Outbox 이벤트 조회와 재발행(ADR-0037). 운영자(admin)만 쓴다. 재발행은 이벤트 ID가 식별자이고 상태로 멱등해
 * `Idempotency-Key`를 받지 않는다.
 */
@RestController
@RequestMapping("/api/v1/outbox-events")
class OutboxEventController(
    private val getDeadOutboxEventsUseCase: GetDeadOutboxEventsUseCase,
    private val requeueOutboxEventUseCase: RequeueOutboxEventUseCase,
) {
    @PreAuthorize(InventoryAuthorize.ADMIN)
    @GetMapping
    suspend fun search(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): OutboxEventPageResponse = OutboxEventPageResponse.from(getDeadOutboxEventsUseCase.search(page, size))

    @PreAuthorize(InventoryAuthorize.ADMIN)
    @GetMapping("/{outboxEventId}")
    suspend fun get(
        @PathVariable outboxEventId: Long,
    ): OutboxEventResponse = OutboxEventResponse.from(getDeadOutboxEventsUseCase.getById(outboxEventId))

    @PreAuthorize(InventoryAuthorize.ADMIN)
    @PostMapping("/{outboxEventId}/requeue")
    suspend fun requeue(
        @PathVariable outboxEventId: Long,
    ): OutboxEventResponse = OutboxEventResponse.from(requeueOutboxEventUseCase.requeue(outboxEventId))
}
