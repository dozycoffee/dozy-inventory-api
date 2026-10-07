package com.dozycoffee.inventory.reservation.adapter.`in`.web

import com.dozycoffee.inventory.support.ApiDoc
import org.springframework.restdocs.headers.HeaderDocumentation.headerWithName
import org.springframework.restdocs.payload.JsonFieldType
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.requestFields
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.test.web.reactive.server.EntityExchangeResult
import java.util.function.Consumer

/** 예약 생성 API의 명세. `ReservationControllerTest`가 `.consumeWith(...)`로 붙여 REST Docs 스니펫을 만든다 */
object ReservationApiDocs {
    private const val TAG: String = "Reservation"
    private const val SUMMARY: String = "예약 생성"

    private val idempotencyKey =
        headerWithName("Idempotency-Key").description("멱등 키. 예약 요청마다 하나이며 ASCII 문자열 100자 이하, 대소문자를 구분한다(예: svc-oms-order-1)")

    fun create(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "reservation-create",
            TAG,
            SUMMARY,
            "호출 서비스가 확정한 창고 한 곳에서 주문의 모든 상품을 유통기한이 이른 Lot부터 예약한다. 전체 성공 또는 전체 실패이다. " +
                "`inventory:service`만 호출할 수 있다. 창고 선택은 호출 서비스의 책임이며 후보 창고 목록은 받지 않는다. " +
                "처음 요청과 재요청 모두 200이다. 같은 `Idempotency-Key`로 다시 요청하면 새로 잡지 않고 저장된 예약의 현재 상태를 반환한다" +
                "(그 사이 확정·해제되었으면 바뀐 상태가 나온다). 같은 키에 다른 내용이 오면 409이다. " +
                "만료 시각은 요청이 정하며 채널의 상한을 넘을 수 없다.",
            "CreateReservationRequest",
            "ReservationResponse",
            ApiDoc.authorizationAnd(idempotencyKey),
            requestFields(
                fieldWithPath("warehouseId").type(JsonFieldType.NUMBER).description("호출 서비스가 확정한 창고 ID(WMS)"),
                fieldWithPath("channel").type(JsonFieldType.STRING).description("호출 채널. 최대 50자. 값은 확정되어 있지 않아 형식만 검증하며 채널별 만료 상한의 기준이다"),
                fieldWithPath(
                    "externalOrderId",
                ).type(JsonFieldType.STRING).description("호출 채널의 주문 ID. 최대 100자. 같은 채널의 같은 주문에 살아 있는 예약이 있으면 409"),
                fieldWithPath(
                    "expiresAt",
                ).type(
                    JsonFieldType.STRING,
                ).description("만료 시각. 시간대 오프셋을 포함한 ISO-8601(예: 2026-10-07T21:30:00+09:00)이며 현재 이후이고 채널 상한 이하여야 한다"),
                fieldWithPath("items").type(JsonFieldType.ARRAY).description("예약할 상품. 1~100개이며 상품은 중복될 수 없다"),
                fieldWithPath("items[].productId").type(JsonFieldType.NUMBER).description("상품 ID. 비활성 상품은 409"),
                fieldWithPath("items[].quantity").type(JsonFieldType.NUMBER).description("예약 수량. 1 이상"),
            ),
            responseFields(
                fieldWithPath("reservationId").type(JsonFieldType.NUMBER).description("예약 ID"),
                fieldWithPath(
                    "status",
                ).type(JsonFieldType.STRING).description("예약 상태: RESERVED, CONFIRMED, RELEASED, EXPIRED, FULFILLED. 처음에는 RESERVED"),
                fieldWithPath("warehouseId").type(JsonFieldType.NUMBER).description("창고 ID"),
                fieldWithPath("channel").type(JsonFieldType.STRING).description("호출 채널"),
                fieldWithPath("externalOrderId").type(JsonFieldType.STRING).description("호출 채널의 주문 ID"),
                fieldWithPath("expiresAt").type(JsonFieldType.STRING).optional().description("만료 시각(오프셋 포함). 확정된 예약은 만료되지 않아 null"),
                fieldWithPath("maxExpiresAt").type(JsonFieldType.STRING).description("연장해도 넘을 수 없는 최대 만료 시각(생성 시각 + 채널 상한)"),
                fieldWithPath("items").type(JsonFieldType.ARRAY).description("상품별 예약 결과. 요청한 순서"),
                fieldWithPath("items[].productId").type(JsonFieldType.NUMBER).description("상품 ID"),
                fieldWithPath("items[].requestedQuantity").type(JsonFieldType.NUMBER).description("요청 수량"),
                fieldWithPath("items[].allocations").type(JsonFieldType.ARRAY).description("수량이 할당된 재고 행(Lot)별 내역. 재고 행 ID 오름차순"),
                fieldWithPath("items[].allocations[].inventoryId").type(JsonFieldType.NUMBER).description("할당된 재고 행 ID"),
                fieldWithPath("items[].allocations[].quantity").type(JsonFieldType.NUMBER).description("그 행에서 잡은 수량"),
            ),
        )

    fun invalid(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "reservation-create-invalid",
            TAG,
            SUMMARY,
            "요청 값이 올바르지 않으면 400을 응답한다. 필드 검증 실패는 VALIDATION_FAILED와 필드별 errors, " +
                "만료 시각이 현재 이전이거나 채널 상한을 넘으면 INV_INVALID_RESERVATION_EXPIRY, " +
                "멱등 키 형식 오류는 INV_INVALID_IDEMPOTENCY_KEY이다.",
            null,
            "Problem",
            ApiDoc.authorizationAnd(idempotencyKey),
            ApiDoc.problem(withErrors = true),
        )

    fun productNotFound(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "reservation-create-product-not-found",
            TAG,
            SUMMARY,
            "상품이 없으면 404 INV_PRODUCT_NOT_FOUND를 응답하고 아무것도 잡지 않는다.",
            null,
            "Problem",
            ApiDoc.authorizationAnd(idempotencyKey),
            ApiDoc.problem(),
        )

    fun insufficientQuantity(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "reservation-create-insufficient-quantity",
            TAG,
            SUMMARY,
            "창고의 가용 수량이 모자라면 409 INV_INSUFFICIENT_AVAILABLE_QUANTITY를 응답하고 아무것도 잡지 않는다(전체 실패). " +
                "같은 재고에 요청이 몰려 재시도 끝에 밀리면 409 INV_ALLOCATION_CONFLICT이며 호출 서비스가 다시 요청하면 된다.",
            null,
            "Problem",
            ApiDoc.authorizationAnd(idempotencyKey),
            ApiDoc.problem(),
        )

    fun conflict(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "reservation-create-conflict",
            TAG,
            SUMMARY,
            "같은 채널의 같은 주문에 살아 있는 예약이 있는데 다른 멱등 키로 요청하면 409 INV_DUPLICATE_ORDER_RESERVATION, " +
                "비활성 상품이면 409 INV_PRODUCT_NOT_RESERVABLE, " +
                "같은 멱등 키에 다른 내용이 오면 409 INV_IDEMPOTENCY_KEY_CONFLICT를 응답한다.",
            null,
            "Problem",
            ApiDoc.authorizationAnd(idempotencyKey),
            ApiDoc.problem(),
        )
}
