package com.dozycoffee.inventory.reservation.adapter.`in`.web

import com.dozycoffee.inventory.support.ApiDoc
import org.springframework.restdocs.payload.JsonFieldType
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.requestFields
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.restdocs.request.RequestDocumentation.parameterWithName
import org.springframework.restdocs.request.RequestDocumentation.pathParameters
import org.springframework.restdocs.snippet.Snippet
import org.springframework.test.web.reactive.server.EntityExchangeResult
import java.util.function.Consumer

/** 예약 확정·해제·연장 API의 명세. `ReservationTransitionControllerTest`가 `.consumeWith(...)`로 붙여 REST Docs 스니펫을 만든다 */
object ReservationTransitionApiDocs {
    private const val TAG: String = "Reservation"

    private val reservationId: Snippet = pathParameters(parameterWithName("reservationId").description("예약 ID"))

    private val response: Snippet =
        responseFields(
            fieldWithPath("reservationId").type(JsonFieldType.NUMBER).description("예약 ID"),
            fieldWithPath("status").type(JsonFieldType.STRING).description("처리 후 예약 상태: RESERVED, CONFIRMED, RELEASED, EXPIRED, FULFILLED"),
            fieldWithPath("warehouseId").type(JsonFieldType.NUMBER).description("창고 ID"),
            fieldWithPath("channel").type(JsonFieldType.STRING).description("호출 채널"),
            fieldWithPath("externalOrderId").type(JsonFieldType.STRING).description("호출 채널의 주문 ID"),
            fieldWithPath("expiresAt").type(JsonFieldType.STRING).optional().description("만료 시각(오프셋 포함). 확정·해제된 예약은 null"),
            fieldWithPath("maxExpiresAt").type(JsonFieldType.STRING).description("연장해도 넘을 수 없는 최대 만료 시각"),
            fieldWithPath("items").type(JsonFieldType.ARRAY).description("상품별 예약 내역"),
            fieldWithPath("items[].productId").type(JsonFieldType.NUMBER).description("상품 ID"),
            fieldWithPath("items[].requestedQuantity").type(JsonFieldType.NUMBER).description("요청 수량"),
            fieldWithPath("items[].allocations").type(JsonFieldType.ARRAY).description("수량이 할당된 재고 행(Lot)별 내역"),
            fieldWithPath("items[].allocations[].inventoryId").type(JsonFieldType.NUMBER).description("할당된 재고 행 ID"),
            fieldWithPath("items[].allocations[].quantity").type(JsonFieldType.NUMBER).description("그 행에서 잡은 수량"),
        )

    fun confirm(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "reservation-confirm",
            TAG,
            "예약 확정",
            "확정 전 예약을 확정한다. `inventory:service`만 호출할 수 있다. 확정된 예약은 만료되지 않고 WMS 출고 지시의 대상이 된다. " +
                "`Idempotency-Key`는 없으며 상태로 멱등하다: 이미 확정된 예약은 아무것도 바꾸지 않고 200으로 현재 상태를 반환한다.",
            null,
            "ReservationResponse",
            ApiDoc.authorization,
            reservationId,
            response,
        )

    fun release(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "reservation-release",
            TAG,
            "예약 해제",
            "예약을 해제하고 예약 수량을 가용 수량으로 되돌린다. `inventory:service`만 호출할 수 있다. 확정 전·확정된 예약 모두 해제할 수 있다. " +
                "이미 해제되었거나 만료된 예약은 아무것도 바꾸지 않고 200으로 현재 상태를 반환한다. 출고 완료된 예약은 409이다.",
            null,
            "ReservationResponse",
            ApiDoc.authorization,
            reservationId,
            response,
        )

    fun extend(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "reservation-extend",
            TAG,
            "예약 연장",
            "확정 전 예약의 만료 시각을 늘린다. `inventory:service`만 호출할 수 있다. 새 만료 시각은 현재 만료 시각 이상이고 " +
                "최대 만료 시각(생성 시각 + 채널 상한) 이하여야 한다. 같은 시각이면 아무것도 바꾸지 않고 200으로 현재 상태를 반환한다.",
            "ExtendReservationRequest",
            "ReservationResponse",
            ApiDoc.authorization,
            reservationId,
            requestFields(
                fieldWithPath(
                    "expiresAt",
                ).type(JsonFieldType.STRING).description("새 만료 시각. 시간대 오프셋을 포함한 ISO-8601(예: 2026-10-07T21:50:00+09:00)"),
            ),
            response,
        )

    fun invalid(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "reservation-extend-invalid",
            TAG,
            "예약 연장",
            "요청 값이 올바르지 않으면 400 VALIDATION_FAILED와 필드별 errors를 응답한다(만료 시각이 없거나 시간대 오프셋이 없는 형식 등).",
            null,
            "Problem",
            ApiDoc.authorization,
            reservationId,
            ApiDoc.problem(withErrors = true),
        )

    fun expiryOutOfRange(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "reservation-extend-expiry-out-of-range",
            TAG,
            "예약 연장",
            "새 만료 시각이 현재 이전이거나 현재 만료 시각보다 이르거나 최대 만료 시각(생성 시각 + 채널 상한)을 넘으면 400 INV_INVALID_RESERVATION_EXPIRY를 응답한다.",
            null,
            "Problem",
            ApiDoc.authorization,
            reservationId,
            ApiDoc.problem(),
        )

    fun notFound(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "reservation-not-found",
            TAG,
            "예약 확정·해제·연장",
            "예약이 없으면 404 INV_RESERVATION_NOT_FOUND를 응답한다.",
            null,
            "Problem",
            ApiDoc.authorization,
            reservationId,
            ApiDoc.problem(),
        )

    fun conflict(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "reservation-transition-conflict",
            TAG,
            "예약 확정·해제·연장",
            "현재 상태에서 할 수 없는 전이는 409 INV_INVALID_RESERVATION_STATE(예: 해제된 예약의 확정, 출고 완료된 예약의 해제, 확정된 예약의 연장), " +
                "만료 시각이 지난 확정 전 예약의 확정·연장은 409 INV_RESERVATION_EXPIRED, " +
                "다른 요청과 계속 겹쳐 처리하지 못하면 409 INV_RESERVATION_CHANGED_CONCURRENTLY이며 호출 서비스가 다시 요청하면 된다.",
            null,
            "Problem",
            ApiDoc.authorization,
            reservationId,
            ApiDoc.problem(),
        )
}
