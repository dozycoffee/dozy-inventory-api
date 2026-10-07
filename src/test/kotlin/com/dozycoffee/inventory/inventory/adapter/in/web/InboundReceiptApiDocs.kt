package com.dozycoffee.inventory.inventory.adapter.`in`.web

import com.dozycoffee.inventory.support.ApiDoc
import org.springframework.restdocs.headers.HeaderDocumentation.headerWithName
import org.springframework.restdocs.payload.JsonFieldType
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.requestFields
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.test.web.reactive.server.EntityExchangeResult
import java.util.function.Consumer

/** 입고 확정 API의 명세. `InboundReceiptControllerTest`가 `.consumeWith(...)`로 붙여 REST Docs 스니펫을 만든다 */
object InboundReceiptApiDocs {
    private const val TAG: String = "InboundReceipt"
    private const val SUMMARY: String = "입고 확정 반영"

    private val idempotencyKey =
        headerWithName("Idempotency-Key").description("멱등 키. 입고 품목마다 하나이며 ASCII 문자열 100자 이하, 대소문자를 구분한다(예: wms-inbound-item-77)")

    fun confirm(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "inbound-receipt-confirm",
            TAG,
            SUMMARY,
            "WMS가 확정한 입고 한 품목을 재고에 반영한다. `inventory:service`만 호출할 수 있다. " +
                "같은 상품의 Lot 번호를 처음 받으면 Lot을 등록하고 있으면 재사용한다. " +
                "같은 `Idempotency-Key`로 다시 요청하면 반영하지 않고 처음 응답과 같은 결과를 200으로 반환한다.",
            "ConfirmInboundReceiptRequest",
            "InboundReceiptResponse",
            ApiDoc.authorizationAnd(idempotencyKey),
            requestFields(
                fieldWithPath("warehouseId").type(JsonFieldType.NUMBER).description("창고 ID(WMS)"),
                fieldWithPath("productId").type(JsonFieldType.NUMBER).description("상품 ID"),
                fieldWithPath("quantity").type(JsonFieldType.NUMBER).description("입고 수량. 1 이상"),
                fieldWithPath("qualityStatus").type(JsonFieldType.STRING).description("WMS 검수 판정: NORMAL, DEFECTIVE"),
                fieldWithPath("lotNumber").type(JsonFieldType.STRING).description("공급사가 부여한 Lot 번호. 최대 50자, 대소문자를 구분한다"),
                fieldWithPath("manufactureDate").type(JsonFieldType.STRING).optional().description("제조일자(yyyy-MM-dd). 기존 Lot과 다르면 409"),
                fieldWithPath("expirationDate").type(JsonFieldType.STRING).optional().description("유통기한(yyyy-MM-dd). 기존 Lot과 다르면 409"),
                fieldWithPath("inboundItemId").type(JsonFieldType.NUMBER).description("WMS의 입고 상품 ID(원인 문서 ID)"),
            ),
            responseFields(
                fieldWithPath("inventoryId").type(JsonFieldType.NUMBER).description("반영된 재고 행 ID"),
                fieldWithPath("warehouseId").type(JsonFieldType.NUMBER).description("창고 ID"),
                fieldWithPath("productId").type(JsonFieldType.NUMBER).description("상품 ID"),
                fieldWithPath("lotId").type(JsonFieldType.NUMBER).description("Lot ID"),
                fieldWithPath("qualityStatus").type(JsonFieldType.STRING).description("반영된 품질 상태"),
                fieldWithPath("quantityChange").type(JsonFieldType.NUMBER).description("이번 입고의 변동량"),
                fieldWithPath("quantityAfter").type(JsonFieldType.NUMBER).description("반영 직후의 총 수량. 재요청에도 처음 응답과 같은 값이며 WMS가 자기 수량과 비교한다"),
                fieldWithPath("inventoryHistoryId").type(JsonFieldType.NUMBER).description("저장된 재고 이력 ID"),
            ),
        )

    fun invalid(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "inbound-receipt-confirm-invalid",
            TAG,
            SUMMARY,
            "요청 값이 올바르지 않으면 400을 응답한다. 필드 검증 실패는 VALIDATION_FAILED와 필드별 errors, " +
                "수량 1 미만은 INV_INVALID_QUANTITY, 폐기 예정 품질 상태는 INV_INVALID_INBOUND_QUALITY_STATUS, 멱등 키 형식 오류는 INV_INVALID_IDEMPOTENCY_KEY이다.",
            null,
            "Problem",
            ApiDoc.authorizationAnd(idempotencyKey),
            ApiDoc.problem(withErrors = true),
        )

    fun productNotFound(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "inbound-receipt-confirm-product-not-found",
            TAG,
            SUMMARY,
            "상품이 없으면 404 INV_PRODUCT_NOT_FOUND를 응답하고 아무것도 저장하지 않는다. 비활성 상품은 반영한다.",
            null,
            "Problem",
            ApiDoc.authorizationAnd(idempotencyKey),
            ApiDoc.problem(),
        )

    fun lotMismatch(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "inbound-receipt-confirm-lot-mismatch",
            TAG,
            SUMMARY,
            "이미 등록된 Lot과 제조일자 또는 유통기한이 다르면 409 INV_LOT_MISMATCH를 응답한다. " +
                "같은 멱등 키에 다른 내용이 오면 409 INV_IDEMPOTENCY_KEY_CONFLICT를 응답한다.",
            null,
            "Problem",
            ApiDoc.authorizationAnd(idempotencyKey),
            ApiDoc.problem(),
        )
}
