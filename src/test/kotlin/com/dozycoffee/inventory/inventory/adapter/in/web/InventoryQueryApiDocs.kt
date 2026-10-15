package com.dozycoffee.inventory.inventory.adapter.`in`.web

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

/** 관리자 재고 현황·상세·이력 조회 API의 명세. `InventoryQueryControllerTest`가 `.consumeWith(...)`로 붙여 REST Docs 스니펫을 만든다 */
object InventoryQueryApiDocs {
    private const val TAG: String = "Inventory"

    private val pathId: Snippet = pathParameters(parameterWithName("inventoryId").description("재고 행 ID"))

    private val inventoryFields =
        listOf(
            fieldWithPath("inventoryId").type(JsonFieldType.NUMBER).description("재고 행 ID"),
            fieldWithPath("warehouseId").type(JsonFieldType.NUMBER).description("창고 ID"),
            fieldWithPath("productId").type(JsonFieldType.NUMBER).description("상품 ID"),
            fieldWithPath("productCode").type(JsonFieldType.STRING).description("상품 코드(상품 마스터)"),
            fieldWithPath("productName").type(JsonFieldType.STRING).description("상품 이름(상품 마스터)"),
            fieldWithPath("unit").type(JsonFieldType.STRING).description("상품 단위(상품 마스터). 예: KG, L, EA"),
            fieldWithPath("lotId").type(JsonFieldType.NUMBER).description("Lot ID"),
            fieldWithPath("lotNumber").type(JsonFieldType.STRING).description("공급사가 부여한 Lot 번호"),
            fieldWithPath("manufactureDate").type(JsonFieldType.STRING).optional().description("제조일자(yyyy-MM-dd). 없을 수 있다"),
            fieldWithPath("expirationDate").type(JsonFieldType.STRING).optional().description("유통기한(yyyy-MM-dd). 없는 Lot이면 null"),
            fieldWithPath("lotStatus").type(JsonFieldType.STRING).description("Lot 상태: NORMAL, EXPIRING_SOON, EXPIRED"),
            fieldWithPath("qualityStatus").type(JsonFieldType.STRING).description("품질 상태: NORMAL, DEFECTIVE, DISPOSAL_SCHEDULED"),
            fieldWithPath("quantity").type(JsonFieldType.NUMBER).description("총 수량(예약 수량 포함)"),
            fieldWithPath("reservedQuantity").type(JsonFieldType.NUMBER).description("예약 수량"),
            fieldWithPath("availableQuantity").type(JsonFieldType.NUMBER).description("총 수량 − 예약 수량. 품질 상태와 보류는 반영하지 않은 행 자체의 값이다"),
            fieldWithPath("allocationHold").type(JsonFieldType.BOOLEAN).description("신규 할당 보류 여부"),
            fieldWithPath("holdReason").type(JsonFieldType.STRING).optional().description("보류 사유. 보류가 아니면 null"),
            fieldWithPath("heldAt").type(JsonFieldType.STRING).optional().description("보류 시각. 보류가 아니면 null"),
            fieldWithPath("createdAt").type(JsonFieldType.STRING).description("행 생성 시각"),
            fieldWithPath("updatedAt").type(JsonFieldType.STRING).description("마지막 변경 시각"),
        )

    fun search(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "inventory-search",
            TAG,
            "관리자 재고 현황 조회",
            "재고 행(창고 × Lot × 품질 상태) 단위의 재고 현황을 페이지로 나눠 조회한다. `inventory:admin`과 `inventory:warehouse_manager`가 호출할 수 있고, " +
                "창고 관리자는 배정된 창고의 행만 본다(창고 조건을 생략하면 배정된 창고로 자동 제한되고 배정되지 않은 창고를 지정하면 403). " +
                "조건은 모두 선택이고 주어진 조건을 모두 만족하는 행을 주며, 조건 없이 부르면 전체를 준다. " +
                "`창고, 상품, 유통기한(없는 Lot은 마지막), 재고 행 ID` 순서이다. 조회 결과는 약속이 아니며 약속은 예약으로만 한다.",
            null,
            "InventoryPageResponse",
            ApiDoc.authorization,
            queryParameters(
                parameterWithName("warehouseId").optional().description("창고 ID"),
                parameterWithName("productId").optional().description("상품 ID"),
                parameterWithName("qualityStatus").optional().description("품질 상태: NORMAL, DEFECTIVE, DISPOSAL_SCHEDULED"),
                parameterWithName("lotStatus").optional().description("Lot 상태: NORMAL, EXPIRING_SOON, EXPIRED"),
                parameterWithName("allocationHold").optional().description("신규 할당 보류 여부(true, false)"),
                parameterWithName("page").optional().description("페이지 번호. 0부터 시작하며 기본 0"),
                parameterWithName("size").optional().description("페이지 크기. 1~100이며 기본 20"),
            ),
            responseFields(
                fieldWithPath("items").type(JsonFieldType.ARRAY).description("재고 행 목록"),
                fieldWithPath("page").type(JsonFieldType.NUMBER).description("페이지 번호"),
                fieldWithPath("size").type(JsonFieldType.NUMBER).description("페이지 크기"),
                fieldWithPath("totalElements").type(JsonFieldType.NUMBER).description("조건에 맞는 전체 재고 행 수"),
                fieldWithPath("totalPages").type(JsonFieldType.NUMBER).description("전체 페이지 수"),
            ).andWithPrefix("items[].", inventoryFields),
        )

    fun summary(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "inventory-summary",
            TAG,
            "관리자 상품별 재고 요약 조회",
            "재고 현황과 같은 조건으로 거른 재고 행을 상품 단위로 합쳐 페이지로 나눠 조회한다. 한 줄이 상품 하나이고 상품 ID 순서이다. " +
                "`inventory:admin`과 `inventory:warehouse_manager`가 호출할 수 있고, 창고 관리자는 배정된 창고의 행만 합친다" +
                "(창고 조건을 생략하면 배정된 창고로 자동 제한되고 배정되지 않은 창고를 지정하면 403). " +
                "수량은 조건에 맞는 재고 행의 합이며 `availableQuantity`는 총 수량 − 예약 수량이다(품질 상태와 보류는 반영하지 않는다). " +
                "`defectiveQuantity`, `heldQuantity`, `expiredQuantity`, `expiringSoonQuantity`는 각각 품질 상태가 불량인 행, " +
                "할당 보류 중인 행, Lot이 유통기한 만료·임박인 행의 총 수량이고 서로 겹칠 수 있다. " +
                "상품별 재고 행은 `GET /api/v1/inventories?productId=`로 이어서 본다.",
            null,
            "InventorySummaryPageResponse",
            ApiDoc.authorization,
            queryParameters(
                parameterWithName("warehouseId").optional().description("창고 ID"),
                parameterWithName("productId").optional().description("상품 ID"),
                parameterWithName("qualityStatus").optional().description("품질 상태: NORMAL, DEFECTIVE, DISPOSAL_SCHEDULED"),
                parameterWithName("lotStatus").optional().description("Lot 상태: NORMAL, EXPIRING_SOON, EXPIRED"),
                parameterWithName("allocationHold").optional().description("신규 할당 보류 여부(true, false)"),
                parameterWithName("page").optional().description("페이지 번호. 0부터 시작하며 기본 0"),
                parameterWithName("size").optional().description("페이지 크기. 1~100이며 기본 20"),
            ),
            responseFields(
                fieldWithPath("items").type(JsonFieldType.ARRAY).description("상품별 요약 목록. 상품 ID 순서"),
                fieldWithPath("page").type(JsonFieldType.NUMBER).description("페이지 번호"),
                fieldWithPath("size").type(JsonFieldType.NUMBER).description("페이지 크기"),
                fieldWithPath("totalElements").type(JsonFieldType.NUMBER).description("조건에 맞는 재고 행이 있는 전체 상품 수"),
                fieldWithPath("totalPages").type(JsonFieldType.NUMBER).description("전체 페이지 수"),
                fieldWithPath("items[].productId").type(JsonFieldType.NUMBER).description("상품 ID"),
                fieldWithPath("items[].productCode").type(JsonFieldType.STRING).description("상품 코드(상품 마스터)"),
                fieldWithPath("items[].productName").type(JsonFieldType.STRING).description("상품 이름(상품 마스터)"),
                fieldWithPath("items[].unit").type(JsonFieldType.STRING).description("상품 단위(상품 마스터)"),
                fieldWithPath("items[].warehouseCount").type(JsonFieldType.NUMBER).description("조건에 맞는 재고 행이 있는 창고 수"),
                fieldWithPath("items[].rowCount").type(JsonFieldType.NUMBER).description("조건에 맞는 재고 행(창고 × Lot × 품질 상태) 수"),
                fieldWithPath("items[].quantity").type(JsonFieldType.NUMBER).description("총 수량 합(예약 수량 포함)"),
                fieldWithPath("items[].reservedQuantity").type(JsonFieldType.NUMBER).description("예약 수량 합"),
                fieldWithPath("items[].availableQuantity").type(JsonFieldType.NUMBER).description("총 수량 − 예약 수량. 품질 상태와 보류는 반영하지 않는다"),
                fieldWithPath("items[].defectiveQuantity").type(JsonFieldType.NUMBER).description("품질 상태가 불량(DEFECTIVE)인 행의 총 수량"),
                fieldWithPath("items[].heldQuantity").type(JsonFieldType.NUMBER).description("신규 할당이 보류된 행의 총 수량"),
                fieldWithPath("items[].expiredQuantity").type(JsonFieldType.NUMBER).description("Lot 상태가 유통기한 만료(EXPIRED)인 행의 총 수량"),
                fieldWithPath(
                    "items[].expiringSoonQuantity",
                ).type(JsonFieldType.NUMBER).description("Lot 상태가 유통기한 임박(EXPIRING_SOON)인 행의 총 수량"),
            ),
        )

    fun detail(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "inventory-detail",
            TAG,
            "관리자 재고 상세 조회",
            "재고 행 하나의 Lot, 수량, 예약, 보류 정보를 조회한다. `inventory:admin`과 `inventory:warehouse_manager`가 호출할 수 있고, 창고 관리자는 행의 창고가 배정되지 않았으면 403이다.",
            null,
            "InventoryResponse",
            ApiDoc.authorization,
            pathId,
            responseFields(inventoryFields),
        )

    fun histories(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "inventory-histories",
            TAG,
            "관리자 재고 이력 조회",
            "재고 행의 수량 변동 이력을 최신순(생성 시각, ID 내림차순)으로 페이지를 나눠 조회한다. `inventory:admin`과 `inventory:warehouse_manager`가 호출할 수 있고, " +
                "창고 관리자는 행의 창고가 배정되지 않았으면 403이다. " +
                "이력은 변경하지 않는 원장이며 `quantityAfter`는 변경 직후의 총 수량(예약 수량 제외)이다.",
            null,
            "InventoryHistoryPageResponse",
            ApiDoc.authorization,
            pathId,
            queryParameters(
                parameterWithName("page").optional().description("페이지 번호. 0부터 시작하며 기본 0"),
                parameterWithName("size").optional().description("페이지 크기. 1~100이며 기본 20"),
            ),
            responseFields(
                fieldWithPath("items").type(JsonFieldType.ARRAY).description("이력 목록. 최신순"),
                fieldWithPath("page").type(JsonFieldType.NUMBER).description("페이지 번호"),
                fieldWithPath("size").type(JsonFieldType.NUMBER).description("페이지 크기"),
                fieldWithPath("totalElements").type(JsonFieldType.NUMBER).description("전체 이력 수"),
                fieldWithPath("totalPages").type(JsonFieldType.NUMBER).description("전체 페이지 수"),
                fieldWithPath("items[].inventoryHistoryId").type(JsonFieldType.NUMBER).description("이력 ID"),
                fieldWithPath("items[].historyType")
                    .type(JsonFieldType.STRING)
                    .description("INBOUND, RETURN, OUTBOUND, DISPOSAL, ADJUSTMENT, RECONCILIATION, QUALITY_TRANSFER"),
                fieldWithPath("items[].quantityChange").type(JsonFieldType.NUMBER).description("변동량(증가 +, 감소 −)"),
                fieldWithPath("items[].quantityAfter").type(JsonFieldType.NUMBER).description("변경 후 총 수량"),
                fieldWithPath("items[].referenceType")
                    .type(JsonFieldType.STRING)
                    .description("원인 문서 유형: INBOUND_ITEM, RETURN_ITEM, RESERVATION, DISPOSAL_ITEM, STOCK_ADJUSTMENT_ITEM, LOT_EXPIRATION"),
                fieldWithPath("items[].referenceId").type(JsonFieldType.NUMBER).description("원인 문서 ID"),
                fieldWithPath("items[].idempotencyKey").type(JsonFieldType.STRING).description("요청의 멱등 키"),
                fieldWithPath(
                    "items[].requesterService",
                ).type(JsonFieldType.STRING).description("요청 주체(system client의 principalId 또는 scheduler)"),
                fieldWithPath("items[].createdAt").type(JsonFieldType.STRING).description("이력 생성 시각"),
            ),
        )

    fun invalid(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "inventory-query-invalid",
            TAG,
            "관리자 재고 현황 조회",
            "조회 조건이 올바르지 않으면 400을 응답한다. ID가 양수가 아니거나 페이지가 음수이거나 크기가 1~100이 아니면 INV_INVALID_INVENTORY_QUERY, " +
                "알 수 없는 품질 상태·Lot 상태 값이거나 숫자가 아닌 값이면 VALIDATION_FAILED이다.",
            null,
            "Problem",
            ApiDoc.authorization,
            ApiDoc.problem(),
        )

    fun notFound(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "inventory-query-not-found",
            TAG,
            "관리자 재고 상세 조회",
            "재고 행이 없으면 404 INV_INVENTORY_NOT_FOUND를 응답한다.",
            null,
            "Problem",
            ApiDoc.authorization,
            pathId,
            ApiDoc.problem(),
        )

    fun warehouseAccessDenied(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "inventory-query-warehouse-access-denied",
            TAG,
            "관리자 재고 현황 조회",
            "창고 관리자가 배정되지 않은 창고를 지정하거나 배정되지 않은 창고의 재고 행을 조회하면 403 INV_WAREHOUSE_ACCESS_DENIED를 응답한다. " +
                "소속 사본이 하나도 없는 창고 관리자도 같은 403이다(서버에 ERROR 로그가 남는다).",
            null,
            "Problem",
            ApiDoc.authorization,
            ApiDoc.problem(),
        )
}
