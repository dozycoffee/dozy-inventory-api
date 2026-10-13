package com.dozycoffee.inventory.outbox.application.service

import com.dozycoffee.inventory.global.domain.UuidV7
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.global.trace.TraceContext
import com.dozycoffee.inventory.outbox.application.port.`in`.RecordOutboxEventUseCase
import com.dozycoffee.inventory.outbox.application.port.`in`.command.RecordOutboxEventCommand
import com.dozycoffee.inventory.outbox.application.port.out.OutboxEventRepository
import com.dozycoffee.inventory.outbox.domain.enumeration.AggregateType
import com.dozycoffee.inventory.outbox.domain.exception.OutboxErrorCode
import com.dozycoffee.inventory.outbox.domain.model.OutboxEvent
import org.springframework.stereotype.Service
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Clock
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * 호출한 서비스의 트랜잭션 안에서 이벤트를 저장한다. 트랜잭션을 직접 열지 않는다. 이벤트 ID(UUID v7)와 발생 시각을 이때 만들고,
 * 호출한 쪽이 넘긴 도메인 본문(JSON)을 공통 형식(envelope)으로 감싸 `payload`에 저장한다. 요청의 추적 컨텍스트가
 * 있으면 envelope의 `traceparent`에 담는다. 메타데이터는 값에만 있고 Kafka 헤더는 쓰지 않는다(ADR-0038, ADR-0039).
 */
@Service
class OutboxService(
    private val outboxEventRepository: OutboxEventRepository,
    private val clock: Clock,
    private val jsonMapper: JsonMapper,
) : RecordOutboxEventUseCase {
    override suspend fun record(command: RecordOutboxEventCommand) {
        val aggregateType: AggregateType =
            AggregateType.entries.firstOrNull { it.name == command.aggregateType }
                ?: throw InvalidDomainValueException(OutboxErrorCode.INVALID_OUTBOX_EVENT)
        val eventId: String = UuidV7.generate(clock.millis())
        outboxEventRepository.save(
            OutboxEvent.create(
                aggregateType,
                command.aggregateId,
                command.eventType,
                command.partitionKey,
                envelopeOf(command, eventId),
                eventId,
            ),
        )
    }

    /** 본문이 JSON 객체가 아니거나 이벤트 유형·버전이 잘못되면 거부한다(저장할 때의 검증은 [OutboxEvent.create]가 한 번 더 한다) */
    private suspend fun envelopeOf(
        command: RecordOutboxEventCommand,
        eventId: String,
    ): String? {
        if (command.payload.isBlank() || command.eventType.isBlank() || command.eventVersion < 1) return null
        val data: JsonNode =
            try {
                jsonMapper.readTree(command.payload)
            } catch (e: JacksonException) {
                throw InvalidDomainValueException(OutboxErrorCode.INVALID_OUTBOX_EVENT)
            }
        if (!data.isObject) throw InvalidDomainValueException(OutboxErrorCode.INVALID_OUTBOX_EVENT)
        val envelope: ObjectNode = jsonMapper.createObjectNode()
        envelope.put("eventId", eventId)
        envelope.put("eventType", command.eventType)
        envelope.put("eventVersion", command.eventVersion)
        envelope.put("occurredAt", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(OffsetDateTime.now(clock)))
        envelope.put("source", OutboxEvent.SOURCE)
        TraceContext.current()?.let { envelope.put("traceparent", it) }
        envelope.set("data", withoutNulls(data))
        return jsonMapper.writeValueAsString(envelope)
    }

    /** 값이 `null`인 객체 필드를 중첩 객체와 배열 안까지 지운다(공통 형식은 `null`을 내보내지 않는다). 배열의 원소는 그대로 둔다 */
    private fun withoutNulls(node: JsonNode): JsonNode {
        when {
            node.isObject -> {
                val fields: ObjectNode = node as ObjectNode
                fields.propertyNames().toList().forEach { name ->
                    val child: JsonNode = fields.get(name)
                    if (child.isNull) fields.remove(name) else withoutNulls(child)
                }
            }

            node.isArray -> {
                node.forEach { withoutNulls(it) }
            }
        }
        return node
    }
}
