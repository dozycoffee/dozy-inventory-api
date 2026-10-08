package com.dozycoffee.inventory.outbox.application.service

import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.outbox.application.port.`in`.RecordOutboxEventUseCase
import com.dozycoffee.inventory.outbox.application.port.`in`.command.RecordOutboxEventCommand
import com.dozycoffee.inventory.outbox.application.port.out.OutboxEventRepository
import com.dozycoffee.inventory.outbox.domain.enumeration.AggregateType
import com.dozycoffee.inventory.outbox.domain.exception.OutboxErrorCode
import com.dozycoffee.inventory.outbox.domain.model.OutboxEvent
import org.springframework.stereotype.Service

/** 호출한 서비스의 트랜잭션 안에서 이벤트를 저장한다. 트랜잭션을 직접 열지 않는다 */
@Service
class OutboxService(
    private val outboxEventRepository: OutboxEventRepository,
) : RecordOutboxEventUseCase {
    override suspend fun record(command: RecordOutboxEventCommand) {
        val aggregateType: AggregateType =
            AggregateType.entries.firstOrNull { it.name == command.aggregateType }
                ?: throw InvalidDomainValueException(OutboxErrorCode.INVALID_OUTBOX_EVENT)
        outboxEventRepository.save(
            OutboxEvent.create(aggregateType, command.aggregateId, command.eventType, command.partitionKey, command.payload),
        )
    }
}
