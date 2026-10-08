package com.dozycoffee.inventory.reservation.adapter.`in`.web

import com.dozycoffee.inventory.support.ApiDoc
import org.springframework.restdocs.headers.HeaderDocumentation.headerWithName
import org.springframework.restdocs.payload.JsonFieldType
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.requestFields
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.restdocs.request.RequestDocumentation.parameterWithName
import org.springframework.restdocs.request.RequestDocumentation.pathParameters
import org.springframework.restdocs.snippet.Snippet
import org.springframework.test.web.reactive.server.EntityExchangeResult
import java.util.function.Consumer

/** 출고 확정 API의 명세. `ReservationFulfillmentControllerTest`가 `.consumeWith(...)`로 붙여 REST Docs 스니펫을 만든다 */
object ReservationFulfillmentApiDocs {
    private const val TAG: String = "Reservation"
    private const val SUMMARY: String = "출고 확정"

    private val idempotencyKey =
        headerWithName("Idempotency-Key").description("멱등 키. 출고 확정 요청마다 하나이며 ASCII 문자열 100자 이하, 대소문자를 구분한다(예: wms-outbound-123)")

    private val reservationId: Snippet = pathParameters(parameterWithName("reservationId").description("예약 ID"))

    fun fulfill(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "reservation-fulfill",
            TAG,
            SUMMARY,
            "WMS가 확정한 출고를 예약에 반영한다. `inventory:service`만 호출할 수 있다. 확정(`CONFIRMED`)된 예약만 대상이고, " +
                "예약의 모든 할당 재고 행에 대한 실제 출고 수량을 한 번에 보낸다(부분 출고 없음). 출고한 수량만큼 총 수량과 예약 수량을 함께 줄이고 " +
                "`OUTBOUND` 이력을 남긴다. 출고 수량이 할당보다 적으면(결품) 모자란 만큼은 예약 수량만 가용 수량으로 되돌리며 총 수량은 줄이지 않는다. " +
                "예약은 `FULFILLED`가 된다. 같은 `Idempotency-Key`와 같은 수량으로 다시 요청하면 새로 반영하지 않고 처음과 같은 결과를 200으로 반환한다.",
            "FulfillReservationRequest",
            "FulfillmentResponse",
            ApiDoc.authorizationAnd(idempotencyKey),
            reservationId,
            requestFields(
                fieldWithPath("allocations").type(JsonFieldType.ARRAY).description("예약의 모든 할당 재고 행별 실제 출고 수량. 같은 재고 행을 두 번 담을 수 없다"),
                fieldWithPath(
                    "allocations[].inventoryId",
                ).type(JsonFieldType.NUMBER).description("할당된 재고 행 ID(예약 생성·확정 응답의 allocations[].inventoryId)"),
                fieldWithPath(
                    "allocations[].shippedQuantity",
                ).type(JsonFieldType.NUMBER).description("그 행에서 실제 출고한 수량. 0 이상 할당 수량 이하이며 0이면 그 행은 전량 결품"),
            ),
            responseFields(
                fieldWithPath("reservationId").type(JsonFieldType.NUMBER).description("예약 ID"),
                fieldWithPath("status").type(JsonFieldType.STRING).description("처리 후 예약 상태. 성공하면 FULFILLED"),
                fieldWithPath("allocations").type(JsonFieldType.ARRAY).description("재고 행별 출고 결과. 재고 행 ID 오름차순"),
                fieldWithPath("allocations[].productId").type(JsonFieldType.NUMBER).description("상품 ID"),
                fieldWithPath("allocations[].inventoryId").type(JsonFieldType.NUMBER).description("재고 행 ID"),
                fieldWithPath("allocations[].allocatedQuantity").type(JsonFieldType.NUMBER).description("예약이 그 행에서 잡았던 수량"),
                fieldWithPath("allocations[].shippedQuantity").type(JsonFieldType.NUMBER).description("실제 출고한 수량"),
                fieldWithPath(
                    "allocations[].shortageQuantity",
                ).type(JsonFieldType.NUMBER).description("결품 수량(할당 − 출고). 예약 수량만 가용으로 되돌아가고 총 수량은 그대로다"),
                fieldWithPath("allocations[].quantityAfter")
                    .type(JsonFieldType.NUMBER)
                    .optional()
                    .description("출고한 행의 처리 후 총 수량. 재요청에도 처음 응답과 같은 값이며 WMS가 자기 수량과 비교한다. 출고하지 않은 행은 총 수량이 바뀌지 않아 null"),
            ),
        )

    fun invalid(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "reservation-fulfill-invalid",
            TAG,
            SUMMARY,
            "요청 값이 올바르지 않으면 400을 응답한다. 필드 검증 실패(재고 행 ID가 양수가 아님, 출고 수량이 음수, 목록이 비어 있음)는 VALIDATION_FAILED와 필드별 errors, " +
                "재고 행이 빠지거나 중복·모르는 행이거나 출고 수량이 할당을 넘으면 INV_INVALID_FULFILLMENT, 멱등 키 형식 오류는 INV_INVALID_IDEMPOTENCY_KEY이다.",
            null,
            "Problem",
            ApiDoc.authorizationAnd(idempotencyKey),
            reservationId,
            ApiDoc.problem(withErrors = true),
        )

    fun invalidFulfillment(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "reservation-fulfill-invalid-fulfillment",
            TAG,
            SUMMARY,
            "예약의 할당 재고 행과 맞지 않는 출고 수량은 400 INV_INVALID_FULFILLMENT를 응답하고 아무것도 반영하지 않는다.",
            null,
            "Problem",
            ApiDoc.authorizationAnd(idempotencyKey),
            reservationId,
            ApiDoc.problem(),
        )

    fun notFound(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "reservation-fulfill-not-found",
            TAG,
            SUMMARY,
            "예약이 없으면 404 INV_RESERVATION_NOT_FOUND를 응답한다.",
            null,
            "Problem",
            ApiDoc.authorizationAnd(idempotencyKey),
            reservationId,
            ApiDoc.problem(),
        )

    fun conflict(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "reservation-fulfill-conflict",
            TAG,
            SUMMARY,
            "확정되지 않았거나 해제·만료된 예약, 이미 다른 수량으로 출고 확정된 예약은 409 INV_INVALID_RESERVATION_STATE, " +
                "이미 출고 확정된 예약에 다른 멱등 키로 요청하면 409 INV_IDEMPOTENCY_KEY_CONFLICT, " +
                "다른 요청과 계속 겹쳐 처리하지 못하면 409 INV_RESERVATION_CHANGED_CONCURRENTLY이며 호출 서비스가 다시 요청하면 된다.",
            null,
            "Problem",
            ApiDoc.authorizationAnd(idempotencyKey),
            reservationId,
            ApiDoc.problem(),
        )
}
