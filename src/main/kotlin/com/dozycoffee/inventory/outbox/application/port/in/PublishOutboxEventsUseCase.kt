package com.dozycoffee.inventory.outbox.application.port.`in`

interface PublishOutboxEventsUseCase {
    /**
     * 발행 대기 이벤트를 `outbox_event_id` 순서대로 최대 [batchSize]개 발행하고 발행한 개수를 반환한다. 여러 인스턴스 중 발행기 락을 잡은
     * 하나만 발행하고 나머지는 0을 반환한다. 발행에 실패한 이벤트와 같은 파티션 키의 그 뒤 이벤트는 이번 실행에서 건너뛴다(순서 보장).
     * 같은 토픽의 다른 이벤트가 성공했을 때만 그 실패를 이벤트 고유의 문제로 보고 시도 횟수를 늘리며, [maxAttempts]에 이르면 격리(`DEAD`)한다(ADR-0037).
     */
    suspend fun publishPending(
        batchSize: Int,
        maxAttempts: Int,
    ): Int
}
