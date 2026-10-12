package com.dozycoffee.inventory.outbox.adapter.`in`.web

import com.dozycoffee.inventory.support.ApiDoc
import org.springframework.restdocs.payload.FieldDescriptor
import org.springframework.restdocs.payload.JsonFieldType
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.restdocs.request.RequestDocumentation.parameterWithName
import org.springframework.restdocs.request.RequestDocumentation.pathParameters
import org.springframework.restdocs.request.RequestDocumentation.queryParameters
import org.springframework.restdocs.snippet.Snippet
import org.springframework.test.web.reactive.server.EntityExchangeResult
import java.util.function.Consumer

/** 격리된 Outbox 이벤트 조회·재발행 API의 명세. `OutboxEventControllerTest`가 `.consumeWith(...)`로 붙여 REST Docs 스니펫을 만든다 */
object OutboxEventApiDocs {
    private const val TAG: String = "OutboxEvent"

    private val pathId: Snippet = pathParameters(parameterWithName("outboxEventId").description("Outbox 이벤트 ID"))

    private fun eventFields(prefix: String = ""): List<FieldDescriptor> =
        listOf(
            fieldWithPath("${prefix}outboxEventId").type(JsonFieldType.NUMBER).description("Outbox 이벤트 ID"),
            fieldWithPath(
                "${prefix}aggregateType",
            ).type(JsonFieldType.STRING).description("집계 유형(토픽을 정한다): INVENTORY, RESERVATION, LOT, PRODUCT, ADJUSTMENT"),
            fieldWithPath("${prefix}aggregateId").type(JsonFieldType.NUMBER).description("집계 ID"),
            fieldWithPath("${prefix}eventType").type(JsonFieldType.STRING).description("이벤트 유형"),
            fieldWithPath("${prefix}partitionKey").type(JsonFieldType.STRING).description("Kafka 파티션 키. 같은 키는 순서대로 발행된다"),
            fieldWithPath("${prefix}payload").type(JsonFieldType.STRING).description("이벤트 payload(JSON 문자열)"),
            fieldWithPath("${prefix}status").type(JsonFieldType.STRING).description("상태. 이 API가 다루는 이벤트는 항상 DEAD(격리)다. 재발행 응답은 PENDING"),
            fieldWithPath("${prefix}attemptCount").type(JsonFieldType.NUMBER).description("이벤트 고유의 발행 실패 횟수. 재발행하면 0으로 돌아간다"),
            fieldWithPath("${prefix}lastError").type(JsonFieldType.STRING).optional().description("마지막 발행 오류(최대 500자). 재발행하면 null"),
            fieldWithPath("${prefix}createdAt").type(JsonFieldType.STRING).optional().description("이벤트가 기록된 시각"),
            fieldWithPath("${prefix}deadAt").type(JsonFieldType.STRING).optional().description("격리된 시각. 재발행하면 null"),
        )

    fun search(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "outbox-event-search",
            TAG,
            "격리된 Outbox 이벤트 목록 조회",
            "발행에 계속 실패해 격리(DEAD)된 이벤트를 최신순 페이지로 조회한다. `inventory:admin`만 호출할 수 있다.",
            null,
            "OutboxEventPageResponse",
            ApiDoc.authorization,
            queryParameters(
                parameterWithName("page").optional().description("페이지 번호. 0부터 시작하며 기본 0"),
                parameterWithName("size").optional().description("페이지 크기. 1~100이며 기본 20"),
            ),
            responseFields(
                fieldWithPath("items").type(JsonFieldType.ARRAY).description("격리된 이벤트 목록"),
                fieldWithPath("page").type(JsonFieldType.NUMBER).description("현재 페이지(0부터)"),
                fieldWithPath("size").type(JsonFieldType.NUMBER).description("페이지 크기"),
                fieldWithPath("totalElements").type(JsonFieldType.NUMBER).description("격리된 전체 이벤트 수"),
                fieldWithPath("totalPages").type(JsonFieldType.NUMBER).description("전체 페이지 수"),
            ).andWithPrefix("items[].", *eventFields().toTypedArray()),
        )

    fun detail(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "outbox-event-detail",
            TAG,
            "격리된 Outbox 이벤트 상세 조회",
            "격리된 이벤트 하나를 조회한다. 없거나 격리 상태가 아니면 404다. `inventory:admin`만 호출할 수 있다.",
            null,
            "OutboxEventResponse",
            ApiDoc.authorization,
            pathId,
            responseFields(eventFields()),
        )

    fun requeue(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "outbox-event-requeue",
            TAG,
            "격리된 Outbox 이벤트 재발행",
            "격리된 이벤트를 발행 대기로 되돌려 발행기가 다시 Kafka로 내보내게 한다. 시도 횟수와 오류는 지워진다. 원인(예: 너무 큰 payload, 토픽 설정)을 " +
                "고친 뒤에 호출한다. 격리 상태가 아니면 409, 없으면 404다. `inventory:admin`만 호출할 수 있다.",
            null,
            "OutboxEventResponse",
            ApiDoc.authorization,
            pathId,
            responseFields(eventFields()),
        )

    fun notFound(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "outbox-event-not-found",
            TAG,
            "Outbox 이벤트 재발행",
            "이벤트가 없으면 404 INV_OUTBOX_EVENT_NOT_FOUND를 응답한다.",
            null,
            "Problem",
            ApiDoc.authorization,
            pathId,
            ApiDoc.problem(),
        )

    fun conflict(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "outbox-event-conflict",
            TAG,
            "Outbox 이벤트 재발행",
            "격리 상태가 아닌 이벤트(발행 대기 중이거나 이미 발행됨, 먼저 재발행됨)는 409 INV_INVALID_OUTBOX_STATE를 응답한다.",
            null,
            "Problem",
            ApiDoc.authorization,
            pathId,
            ApiDoc.problem(),
        )
}
