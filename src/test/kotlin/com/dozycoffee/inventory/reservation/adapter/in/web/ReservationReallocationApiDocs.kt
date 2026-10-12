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

/** Lot 재할당 API의 명세. `ReservationReallocationControllerTest`가 `.consumeWith(...)`로 붙여 REST Docs 스니펫을 만든다 */
object ReservationReallocationApiDocs {
    private const val TAG: String = "Reservation"
    private const val SUMMARY: String = "Lot 재할당"

    private val idempotencyKey =
        headerWithName("Idempotency-Key").description("멱등 키. 재할당 요청마다 하나이며 ASCII 문자열 100자 이하, 대소문자를 구분한다(예: wms-realloc-123)")

    private val reservationId: Snippet = pathParameters(parameterWithName("reservationId").description("예약 ID"))

    fun reallocate(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "reservation-reallocate",
            TAG,
            SUMMARY,
            "WMS가 확정된 예약에서 피킹할 수 없는 Lot을 보고하면 그 부족 수량을 같은 창고의 다른 Lot으로 옮긴다. `inventory:service`만 호출할 수 있다. " +
                "확정(`CONFIRMED`)된 예약만 대상이다. 부족이 보고된 재고 행은 먼저 신규 할당에서 제외(보류)하고, 대체 Lot은 예약 생성과 같은 규칙(유통기한 이른 순)으로 " +
                "inventory가 고른다. 부족 행의 예약 수량은 가용 수량으로 돌아가고 총 수량은 그대로다(원인은 실사 조정·대사로 정리한다). " +
                "응답은 재할당 뒤의 예약 전체 할당이며 WMS는 이 목록으로 피킹 지시를 바꾼다. 같은 `Idempotency-Key`와 같은 내용으로 다시 요청하면 새로 반영하지 않고 " +
                "처음과 같은 결과를 200으로 반환한다.",
            "ReallocateReservationRequest",
            "ReallocationResponse",
            ApiDoc.authorizationAnd(idempotencyKey),
            reservationId,
            requestFields(
                fieldWithPath("shortages").type(JsonFieldType.ARRAY).description("피킹할 수 없는 재고 행별 부족 수량. 같은 재고 행을 두 번 담을 수 없다"),
                fieldWithPath(
                    "shortages[].inventoryId",
                ).type(JsonFieldType.NUMBER).description("부족이 보고된 재고 행 ID(예약 생성·확정 응답의 allocations[].inventoryId)"),
                fieldWithPath(
                    "shortages[].shortageQuantity",
                ).type(JsonFieldType.NUMBER).description("다른 Lot으로 옮길 수량. 1 이상 그 행의 할당 수량 이하"),
            ),
            responseFields(
                fieldWithPath("reservationId").type(JsonFieldType.NUMBER).description("예약 ID"),
                fieldWithPath("status").type(JsonFieldType.STRING).description("예약 상태. 재할당은 확정된 예약만 하므로 CONFIRMED"),
                fieldWithPath("heldInventoryIds").type(JsonFieldType.ARRAY).description("부족이 보고되어 신규 할당에서 제외된 재고 행 ID. 오름차순"),
                fieldWithPath("items").type(JsonFieldType.ARRAY).description("재할당 뒤의 상품별 할당"),
                fieldWithPath("items[].productId").type(JsonFieldType.NUMBER).description("상품 ID"),
                fieldWithPath("items[].allocations").type(JsonFieldType.ARRAY).description("그 상품의 재고 행별 할당. 수량이 0이 된 행은 없다"),
                fieldWithPath("items[].allocations[].inventoryId").type(JsonFieldType.NUMBER).description("재고 행 ID"),
                fieldWithPath("items[].allocations[].lotId").type(JsonFieldType.NUMBER).description("Lot ID"),
                fieldWithPath("items[].allocations[].lotNumber").type(JsonFieldType.STRING).description("Lot 번호"),
                fieldWithPath("items[].allocations[].expirationDate")
                    .type(JsonFieldType.STRING)
                    .optional()
                    .description("Lot의 유통기한(yyyy-MM-dd). 유통기한이 없는 Lot이면 null"),
                fieldWithPath("items[].allocations[].quantity").type(JsonFieldType.NUMBER).description("그 행에서 이 예약이 잡은 수량"),
            ),
        )

    fun invalid(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "reservation-reallocate-invalid",
            TAG,
            SUMMARY,
            "요청 값이 올바르지 않으면 400을 응답한다. 필드 검증 실패(재고 행 ID나 부족 수량이 양수가 아님, 목록이 비어 있음)는 VALIDATION_FAILED와 필드별 errors, " +
                "예약에 없는 재고 행이거나 중복되거나 부족 수량이 할당 수량을 넘으면 INV_INVALID_REALLOCATION, 멱등 키 형식 오류는 INV_INVALID_IDEMPOTENCY_KEY이다. " +
                "입력이 잘못된 요청은 보류하기 전에 거부한다.",
            null,
            "Problem",
            ApiDoc.authorizationAnd(idempotencyKey),
            reservationId,
            ApiDoc.problem(withErrors = true),
        )

    fun notFound(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "reservation-reallocate-not-found",
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
            "reservation-reallocate-conflict",
            TAG,
            SUMMARY,
            "확정되지 않았거나 해제·만료·출고 완료된 예약은 409 INV_INVALID_RESERVATION_STATE, " +
                "대체 Lot의 가용 재고가 모자라면 409 INV_INSUFFICIENT_AVAILABLE_QUANTITY(이때 부족이 보고된 행의 보류는 남는다. " +
                "재고를 보충한 뒤 새 멱등 키로 다시 요청한다), 같은 멱등 키에 다른 부족 내역이면 409 INV_IDEMPOTENCY_KEY_CONFLICT, " +
                "다른 요청과 계속 겹쳐 처리하지 못하면 409 INV_RESERVATION_CHANGED_CONCURRENTLY이며 호출 서비스가 다시 요청하면 된다.",
            null,
            "Problem",
            ApiDoc.authorizationAnd(idempotencyKey),
            reservationId,
            ApiDoc.problem(),
        )
}
