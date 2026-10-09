package com.dozycoffee.inventory.outbox.application.port.`in`

import java.time.Duration

interface CleanUpOutboxEventsUseCase {
    /**
     * 발행 완료 후 [retention]이 지난 이벤트를 `outbox_event_id` 순서로 최대 [batchSize]개 삭제하고 삭제한 개수를 반환한다.
     * 발행 대기 이벤트는 오래되어도 지우지 않는다. 여러 인스턴스가 동시에 호출해도 같은 행을 두 번 지우지 못해 안전하다.
     */
    suspend fun cleanUp(
        retention: Duration,
        batchSize: Int,
    ): Int
}
