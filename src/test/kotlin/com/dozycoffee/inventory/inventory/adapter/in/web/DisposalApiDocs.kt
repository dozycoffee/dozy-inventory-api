package com.dozycoffee.inventory.inventory.adapter.`in`.web

import com.dozycoffee.inventory.support.ApiDoc
import org.springframework.restdocs.headers.HeaderDocumentation.headerWithName
import org.springframework.restdocs.payload.JsonFieldType
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.requestFields
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.test.web.reactive.server.EntityExchangeResult
import java.util.function.Consumer

/** 폐기 확정 API의 명세. `DisposalControllerTest`가 `.consumeWith(...)`로 붙여 REST Docs 스니펫을 만든다 */
object DisposalApiDocs {
    private const val TAG: String = "Disposal"
    private const val SUMMARY: String = "폐기 확정 반영"

    private val idempotencyKey =
        headerWithName("Idempotency-Key").description("멱등 키. 폐기 품목마다 하나이며 ASCII 문자열 100자 이하, 대소문자를 구분한다(예: wms-disposal-item-5)")

    fun confirm(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "disposal-confirm",
            TAG,
            SUMMARY,
            "WMS가 확정한 폐기 한 품목을 재고에서 차감한다. `inventory:service`만 호출할 수 있다. " +
                "불량(DEFECTIVE) 또는 폐기 예정(DISPOSAL_SCHEDULED) 재고만 폐기할 수 있고 정상(NORMAL) 재고는 거부한다. " +
                "폐기 수량은 그 행의 가용 수량 안이어야 한다. 같은 `Idempotency-Key`로 다시 요청하면 차감하지 않고 처음 응답과 같은 결과를 200으로 반환한다.",
            "ConfirmDisposalRequest",
            "DisposalResponse",
            ApiDoc.authorizationAnd(idempotencyKey),
            requestFields(
                fieldWithPath("warehouseId").type(JsonFieldType.NUMBER).description("창고 ID(WMS)"),
                fieldWithPath("productId").type(JsonFieldType.NUMBER).description("상품 ID"),
                fieldWithPath("lotNumber").type(JsonFieldType.STRING).description("공급사가 부여한 Lot 번호. 이미 등록된 Lot이어야 한다(최대 50자, 대소문자 구분)"),
                fieldWithPath("qualityStatus").type(JsonFieldType.STRING).description("폐기할 재고의 품질 상태: DEFECTIVE, DISPOSAL_SCHEDULED"),
                fieldWithPath("quantity").type(JsonFieldType.NUMBER).description("폐기 수량. 1 이상"),
                fieldWithPath("disposalItemId").type(JsonFieldType.NUMBER).description("WMS의 폐기 상품 ID(원인 문서 ID)"),
            ),
            responseFields(
                fieldWithPath("inventoryId").type(JsonFieldType.NUMBER).description("차감된 재고 행 ID"),
                fieldWithPath("warehouseId").type(JsonFieldType.NUMBER).description("창고 ID"),
                fieldWithPath("productId").type(JsonFieldType.NUMBER).description("상품 ID"),
                fieldWithPath("lotId").type(JsonFieldType.NUMBER).description("Lot ID"),
                fieldWithPath("qualityStatus").type(JsonFieldType.STRING).description("폐기한 재고의 품질 상태"),
                fieldWithPath("quantityChange").type(JsonFieldType.NUMBER).description("이번 폐기의 변동량(음수)"),
                fieldWithPath("quantityAfter").type(JsonFieldType.NUMBER).description("반영 직후의 총 수량. 재요청에도 처음 응답과 같은 값이며 WMS가 자기 수량과 비교한다"),
                fieldWithPath("inventoryHistoryId").type(JsonFieldType.NUMBER).description("저장된 재고 이력 ID"),
            ),
        )

    fun invalid(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "disposal-confirm-invalid",
            TAG,
            SUMMARY,
            "요청 값이 올바르지 않으면 400을 응답한다. 필드 검증 실패는 VALIDATION_FAILED와 필드별 errors, " +
                "정상 품질 재고의 폐기는 INV_INVALID_DISPOSAL_QUALITY_STATUS, 멱등 키 형식 오류는 INV_INVALID_IDEMPOTENCY_KEY이다.",
            null,
            "Problem",
            ApiDoc.authorizationAnd(idempotencyKey),
            ApiDoc.problem(withErrors = true),
        )

    fun notFound(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "disposal-confirm-not-found",
            TAG,
            SUMMARY,
            "등록되지 않은 Lot이면 404 INV_LOT_NOT_FOUND, 폐기할 재고 행이 없으면 404 INV_INVENTORY_NOT_FOUND를 응답하고 아무것도 차감하지 않는다.",
            null,
            "Problem",
            ApiDoc.authorizationAnd(idempotencyKey),
            ApiDoc.problem(),
        )

    fun conflict(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "disposal-confirm-conflict",
            TAG,
            SUMMARY,
            "폐기 수량이 가용 수량을 넘으면 409 INV_INSUFFICIENT_AVAILABLE_QUANTITY를 응답한다. " +
                "같은 멱등 키에 다른 내용이 오면 409 INV_IDEMPOTENCY_KEY_CONFLICT를 응답한다.",
            null,
            "Problem",
            ApiDoc.authorizationAnd(idempotencyKey),
            ApiDoc.problem(),
        )
}
