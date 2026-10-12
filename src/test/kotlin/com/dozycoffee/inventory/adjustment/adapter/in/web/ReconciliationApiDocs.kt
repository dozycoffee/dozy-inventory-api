package com.dozycoffee.inventory.adjustment.adapter.`in`.web

import com.dozycoffee.inventory.support.ApiDoc
import org.springframework.restdocs.payload.JsonFieldType
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.restdocs.request.RequestDocumentation.parameterWithName
import org.springframework.restdocs.request.RequestDocumentation.pathParameters
import org.springframework.restdocs.request.RequestDocumentation.queryParameters
import org.springframework.restdocs.snippet.Snippet
import org.springframework.test.web.reactive.server.EntityExchangeResult
import java.util.function.Consumer

/** 대사 보정안 조회·승인·거부 API의 명세. `ReconciliationControllerTest`가 `.consumeWith(...)`로 붙여 REST Docs 스니펫을 만든다 */
object ReconciliationApiDocs {
    private const val TAG: String = "Reconciliation"

    private val pathId: Snippet = pathParameters(parameterWithName("stockAdjustmentId").description("보정안(조정) ID"))

    private fun proposalFields(prefix: String = ""): List<org.springframework.restdocs.payload.FieldDescriptor> =
        listOf(
            fieldWithPath("${prefix}stockAdjustmentId").type(JsonFieldType.NUMBER).description("보정안 ID"),
            fieldWithPath("${prefix}warehouseId").type(JsonFieldType.NUMBER).description("창고 ID"),
            fieldWithPath("${prefix}reconciliationRunId").type(JsonFieldType.NUMBER).optional().description("보정안을 만든 대사 실행 ID"),
            fieldWithPath("${prefix}status").type(JsonFieldType.STRING).description("상태: PENDING(처리 대기), APPLIED(승인·반영), REJECTED(거부)"),
            fieldWithPath("${prefix}productId").type(JsonFieldType.NUMBER).description("상품 ID"),
            fieldWithPath("${prefix}lotId").type(JsonFieldType.NUMBER).description("Lot ID"),
            fieldWithPath("${prefix}lotNumber").type(JsonFieldType.STRING).description("공급사가 부여한 Lot 번호"),
            fieldWithPath("${prefix}qualityStatus").type(JsonFieldType.STRING).description("품질 상태: NORMAL, DEFECTIVE, DISPOSAL_SCHEDULED"),
            fieldWithPath("${prefix}inventoryQuantity").type(JsonFieldType.NUMBER).description("보정안 기준의 inventory 총 수량(예약 수량 제외 아님)"),
            fieldWithPath("${prefix}wmsQuantity").type(JsonFieldType.NUMBER).description("보정안 기준의 WMS 수량"),
            fieldWithPath(
                "${prefix}quantityChange",
            ).type(JsonFieldType.NUMBER).description("변동량(WMS 수량 − inventory 수량). 승인하면 총 수량이 그만큼 바뀐다"),
            fieldWithPath("${prefix}requiresAdmin").type(JsonFieldType.BOOLEAN).description("변동량이 승인 임계치를 넘어 본사 관리자(admin)만 승인할 수 있는지"),
            fieldWithPath("${prefix}inventoryId").type(JsonFieldType.NUMBER).optional().description("반영된 대상 재고 행 ID. 반영 전이면 null"),
            fieldWithPath("${prefix}approvedBy").type(JsonFieldType.STRING).optional().description("승인자(principalId). 승인 전이면 null"),
            fieldWithPath("${prefix}approvedAt").type(JsonFieldType.STRING).optional().description("승인 시각. 승인 전이면 null"),
        )

    fun search(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "reconciliation-search",
            TAG,
            "대사 보정안 목록 조회",
            "하루 1회 대사 배치가 만든 보정안(불일치한 재고 행 하나가 보정안 한 건)을 최신순 페이지로 조회한다. `inventory:admin`과 " +
                "`inventory:warehouse_manager`가 호출할 수 있고 창고 관리자는 배정된 창고의 보정안만 본다(창고를 생략하면 배정된 창고로 제한되고 " +
                "배정되지 않은 창고를 지정하면 403). 창고와 상태 조건은 선택이다.",
            null,
            "ReconciliationProposalPageResponse",
            ApiDoc.authorization,
            queryParameters(
                parameterWithName("warehouseId").optional().description("창고 ID"),
                parameterWithName("status").optional().description("상태: PENDING, APPROVED, REJECTED, APPLIED"),
                parameterWithName("page").optional().description("페이지 번호. 0부터 시작하며 기본 0"),
                parameterWithName("size").optional().description("페이지 크기. 1~100이며 기본 20"),
            ),
            responseFields(
                fieldWithPath("items").type(JsonFieldType.ARRAY).description("보정안 목록"),
                fieldWithPath("page").type(JsonFieldType.NUMBER).description("현재 페이지(0부터)"),
                fieldWithPath("size").type(JsonFieldType.NUMBER).description("페이지 크기"),
                fieldWithPath("totalElements").type(JsonFieldType.NUMBER).description("조건에 맞는 전체 보정안 수"),
                fieldWithPath("totalPages").type(JsonFieldType.NUMBER).description("전체 페이지 수"),
            ).andWithPrefix("items[].", *proposalFields().map { it }.toTypedArray()),
        )

    fun detail(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "reconciliation-detail",
            TAG,
            "대사 보정안 상세 조회",
            "보정안 하나를 조회한다. 대사 보정안이 아니거나 없으면 404, 창고 관리자가 배정되지 않은 창고의 보정안을 조회하면 403이다.",
            null,
            "ReconciliationProposalResponse",
            ApiDoc.authorization,
            pathId,
            responseFields(proposalFields()),
        )

    fun approve(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "reconciliation-approve",
            TAG,
            "대사 보정안 승인",
            "처리 대기 보정안을 승인해 같은 트랜잭션에서 재고에 반영한다(`RECONCILIATION` 이력). `inventory:admin`과 `inventory:warehouse_manager`가 호출할 수 있고, " +
                "변동량이 카테고리 임계치를 넘는 보정안(`requiresAdmin`)은 `admin`만 승인할 수 있다. 승인할 때 WMS 수량과 inventory 수량을 다시 확인해 보정안의 값과 " +
                "다르면 보정안만 새 값으로 고치고 반영하지 않은 채 409(`INV_RECONCILIATION_CHANGED`)이며 새 숫자를 확인하고 다시 승인해야 한다. " +
                "이미 반영된 보정안을 다시 승인하면 바꾸지 않고 현재 상태를 200으로 돌려준다(멱등, `Idempotency-Key` 헤더 없음).",
            null,
            "ReconciliationProposalResponse",
            ApiDoc.authorization,
            pathId,
            responseFields(proposalFields()),
        )

    fun reject(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "reconciliation-reject",
            TAG,
            "대사 보정안 거부",
            "처리 대기 보정안을 거부한다. 수량은 바꾸지 않는다. 승인과 같은 권한이고 임계치와 무관하다. 이미 거부된 보정안은 현재 상태를 200으로 돌려준다.",
            null,
            "ReconciliationProposalResponse",
            ApiDoc.authorization,
            pathId,
            responseFields(proposalFields()),
        )

    fun notFound(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "reconciliation-not-found",
            TAG,
            "대사 보정안 승인",
            "보정안이 없거나 대사 보정안이 아닌 조정(실사 조정)이면 404 INV_ADJUSTMENT_NOT_FOUND를 응답한다.",
            null,
            "Problem",
            ApiDoc.authorization,
            pathId,
            ApiDoc.problem(),
        )

    fun conflict(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "reconciliation-conflict",
            TAG,
            "대사 보정안 승인",
            "거부되었거나 이미 반영된 보정안에 맞지 않는 작업은 409 INV_INVALID_ADJUSTMENT_STATE, 승인할 때 수량이 바뀌었으면 409 INV_RECONCILIATION_CHANGED" +
                "(보정안은 새 숫자로 고쳐졌고 다시 조회해 확인한 뒤 승인한다), 감소가 가용 수량을 넘으면 409 INV_INSUFFICIENT_AVAILABLE_QUANTITY(보정안은 처리 대기로 남는다)이다.",
            null,
            "Problem",
            ApiDoc.authorization,
            pathId,
            ApiDoc.problem(),
        )

    fun forbidden(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "reconciliation-forbidden",
            TAG,
            "대사 보정안 승인",
            "변동량이 승인 임계치를 넘는 보정안을 창고 관리자가 승인하면 403 INV_RECONCILIATION_ADMIN_REQUIRED, 배정되지 않은 창고의 보정안은 403 INV_WAREHOUSE_ACCESS_DENIED이다.",
            null,
            "Problem",
            ApiDoc.authorization,
            pathId,
            ApiDoc.problem(),
        )

    fun wmsUnavailable(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "reconciliation-wms-unavailable",
            TAG,
            "대사 보정안 승인",
            "승인할 때 WMS에서 수량을 조회하지 못하면 502 INV_WMS_UNAVAILABLE을 응답하고 아무것도 바꾸지 않는다. 잠시 뒤 다시 승인한다.",
            null,
            "Problem",
            ApiDoc.authorization,
            pathId,
            ApiDoc.problem(),
        )
}
