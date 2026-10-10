package com.dozycoffee.inventory.adjustment.adapter.`in`.web

import com.dozycoffee.inventory.support.ApiDoc
import org.springframework.restdocs.headers.HeaderDocumentation.headerWithName
import org.springframework.restdocs.payload.JsonFieldType
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.requestFields
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.test.web.reactive.server.EntityExchangeResult
import java.util.function.Consumer

/** 실사 조정 API의 명세. `StockAdjustmentControllerTest`가 `.consumeWith(...)`로 붙여 REST Docs 스니펫을 만든다 */
object StockAdjustmentApiDocs {
    private const val TAG: String = "StockAdjustment"
    private const val SUMMARY: String = "실사 조정 반영"

    private val idempotencyKey =
        headerWithName("Idempotency-Key").description("멱등 키. 실사 건마다 하나이며 ASCII 문자열 100자 이하, 대소문자를 구분한다(예: wms-audit-1001)")

    fun request(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "stock-adjustment-request",
            TAG,
            SUMMARY,
            "WMS가 실사로 계산한 조정 변동량(+/−)을 `상품 × Lot × 품질 상태` 단위로 재고에 반영한다. `inventory:service`만 호출할 수 있다. " +
                "항목은 모두 한 트랜잭션에서 반영하며 하나라도 실패하면 전체를 되돌린다. 증가는 재고 행이 없으면 만들고, " +
                "감소는 행이 있어야 하며 예약 수량을 뺀 가용 수량 안에서만 가능하다. 반영한 행의 할당 보류는 풀린다. " +
                "항목 하나의 변동량 절댓값이 상품 카테고리의 승인 임계치를 넘으면 승인자(`approvedBy`)가 있어야 한다. " +
                "같은 `Idempotency-Key`로 다시 요청하면 반영하지 않고 처음 응답과 같은 결과를 200으로 반환한다.",
            "RequestStockAdjustmentRequest",
            "StockAdjustmentResponse",
            ApiDoc.authorizationAnd(idempotencyKey),
            requestFields(
                fieldWithPath("warehouseId").type(JsonFieldType.NUMBER).description("창고 ID(WMS)"),
                fieldWithPath("auditId").type(JsonFieldType.NUMBER).description("WMS의 실사 건 ID(원인 문서)"),
                fieldWithPath("approvedBy")
                    .type(JsonFieldType.STRING)
                    .optional()
                    .description("승인자. WMS가 자체 절차로 승인한 뒤 보내며 100자 이하. 승인 임계치를 넘는 항목이 있으면 필수"),
                fieldWithPath("items").type(JsonFieldType.ARRAY).description("조정 항목. 1개 이상 500개 이하"),
                fieldWithPath("items[].productId").type(JsonFieldType.NUMBER).description("상품 ID"),
                fieldWithPath(
                    "items[].lotNumber",
                ).type(JsonFieldType.STRING).description("공급사가 부여한 Lot 번호. 이미 등록된 Lot이어야 한다(최대 50자, 대소문자 구분)"),
                fieldWithPath(
                    "items[].qualityStatus",
                ).type(JsonFieldType.STRING).description("품질 상태: NORMAL, DEFECTIVE, DISPOSAL_SCHEDULED"),
                fieldWithPath("items[].quantityChange").type(JsonFieldType.NUMBER).description("변동량. 증가는 양수, 감소는 음수이며 0일 수 없다"),
            ),
            responseFields(
                fieldWithPath("stockAdjustmentId").type(JsonFieldType.NUMBER).description("저장된 조정 ID"),
                fieldWithPath("warehouseId").type(JsonFieldType.NUMBER).description("창고 ID"),
                fieldWithPath("auditId").type(JsonFieldType.NUMBER).description("WMS의 실사 건 ID"),
                fieldWithPath("status").type(JsonFieldType.STRING).description("조정 상태. 실사 조정은 요청 즉시 반영되어 APPLIED"),
                fieldWithPath("approvedBy").type(JsonFieldType.STRING).optional().description("기록된 승인자. 없으면 null"),
                fieldWithPath("items").type(JsonFieldType.ARRAY).description("항목별 결과. Lot, 품질 상태 순서"),
                fieldWithPath("items[].stockAdjustmentItemId").type(JsonFieldType.NUMBER).description("조정 항목 ID(이력의 원인 문서 ID)"),
                fieldWithPath("items[].inventoryId").type(JsonFieldType.NUMBER).description("반영된 재고 행 ID"),
                fieldWithPath("items[].lotId").type(JsonFieldType.NUMBER).description("Lot ID"),
                fieldWithPath("items[].qualityStatus").type(JsonFieldType.STRING).description("품질 상태"),
                fieldWithPath("items[].quantityChange").type(JsonFieldType.NUMBER).description("이번 조정의 변동량"),
                fieldWithPath("items[].quantityAfter")
                    .type(JsonFieldType.NUMBER)
                    .description("반영 직후의 총 수량. 재요청에도 처음 응답과 같은 값이며 WMS가 자기 수량과 비교한다"),
            ),
        )

    fun invalid(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "stock-adjustment-request-invalid",
            TAG,
            SUMMARY,
            "요청 값이 올바르지 않으면 400을 응답한다. 필드 검증 실패는 VALIDATION_FAILED와 필드별 errors, " +
                "변동량 0이거나 같은 상품·Lot·품질 상태가 중복되면 INV_INVALID_STOCK_ADJUSTMENT, 멱등 키 형식 오류는 INV_INVALID_IDEMPOTENCY_KEY이다.",
            null,
            "Problem",
            ApiDoc.authorizationAnd(idempotencyKey),
            ApiDoc.problem(withErrors = true),
        )

    fun approvalRequired(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "stock-adjustment-request-approval-required",
            TAG,
            SUMMARY,
            "변동량 절댓값이 상품 카테고리의 승인 임계치를 넘는 항목이 있는데 `approvedBy`가 없으면 400 INV_APPROVAL_REQUIRED를 응답하고 아무것도 반영하지 않는다.",
            null,
            "Problem",
            ApiDoc.authorizationAnd(idempotencyKey),
            ApiDoc.problem(),
        )

    fun notFound(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "stock-adjustment-request-not-found",
            TAG,
            SUMMARY,
            "상품이 없으면 404 INV_PRODUCT_NOT_FOUND, 등록되지 않은 Lot이면 404 INV_LOT_NOT_FOUND, 감소할 재고 행이 없으면 404 INV_INVENTORY_NOT_FOUND를 응답하고 아무것도 반영하지 않는다.",
            null,
            "Problem",
            ApiDoc.authorizationAnd(idempotencyKey),
            ApiDoc.problem(),
        )

    fun conflict(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "stock-adjustment-request-conflict",
            TAG,
            SUMMARY,
            "감소가 예약 수량을 뺀 가용 수량을 넘으면 409 INV_INSUFFICIENT_AVAILABLE_QUANTITY를 응답하고 조정 전체를 되돌린다. " +
                "같은 멱등 키에 다른 내용이 오면 409 INV_IDEMPOTENCY_KEY_CONFLICT를 응답한다.",
            null,
            "Problem",
            ApiDoc.authorizationAnd(idempotencyKey),
            ApiDoc.problem(),
        )
}
