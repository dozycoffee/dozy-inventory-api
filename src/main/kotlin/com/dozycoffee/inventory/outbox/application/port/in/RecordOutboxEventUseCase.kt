package com.dozycoffee.inventory.outbox.application.port.`in`

import com.dozycoffee.inventory.outbox.application.port.`in`.command.RecordOutboxEventCommand

interface RecordOutboxEventUseCase {
    /** 이벤트를 발행 대기로 저장한다. 호출한 서비스의 트랜잭션 안에서 실행하며, 호출한 쪽이 롤백하면 이벤트도 함께 사라진다 */
    suspend fun record(command: RecordOutboxEventCommand)
}
