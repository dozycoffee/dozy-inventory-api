package com.dozycoffee.inventory.inventory.adapter.`in`.web

import com.dozycoffee.inventory.support.ApiDoc
import org.springframework.restdocs.payload.JsonFieldType
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.restdocs.request.RequestDocumentation.parameterWithName
import org.springframework.restdocs.request.RequestDocumentation.queryParameters
import org.springframework.test.web.reactive.server.EntityExchangeResult
import java.util.function.Consumer

/** 가용 재고 조회 API의 명세. `InventoryAvailabilityControllerTest`가 `.consumeWith(...)`로 붙여 REST Docs 스니펫을 만든다 */
object AvailabilityApiDocs {
    private const val TAG: String = "Inventory"
    private const val SUMMARY: String = "가용 재고 조회"

    fun availability(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "inventory-availability",
            TAG,
            SUMMARY,
            "상품별 가용 수량을 창고별로 나눠 조회한다. `inventory:service`와 `inventory:admin`이 호출할 수 있다. " +
                "가용 수량은 정상 품질이고 할당 보류가 아닌 재고의 (총 수량 − 예약 수량) 합이며 Lot, 유통기한, 위치는 노출하지 않는다. " +
                "`warehouseIds`를 생략하면 그 상품의 재고가 있는 모든 창고를, 지정하면 지정한 모든 창고를(재고가 없으면 0) 보여 준다. " +
                "조회 결과는 약속이 아니며 약속은 예약으로만 한다. 가맹점 기준 최적 창고 선택은 호출 서비스(OMS, Store) 소관이다.",
            null,
            "AvailabilityResponse",
            ApiDoc.authorization,
            queryParameters(
                parameterWithName("productIds").description("상품 ID 목록. 쉼표로 구분하고(예: 1,2,3) 1~100개이며 반복해서 보내도 된다"),
                parameterWithName("warehouseIds").optional().description("창고 ID 목록. 쉼표로 구분하고 1~100개이다. 생략하면 모든 창고"),
            ),
            responseFields(
                fieldWithPath("products").type(JsonFieldType.ARRAY).description("요청한 상품별 가용 수량. 상품 ID 오름차순"),
                fieldWithPath("products[].productId").type(JsonFieldType.NUMBER).description("상품 ID"),
                fieldWithPath("products[].totalAvailableQuantity").type(JsonFieldType.NUMBER).description("조회한 창고들의 가용 수량 합계. 재고가 없으면 0"),
                fieldWithPath("products[].warehouses").type(JsonFieldType.ARRAY).description("창고별 가용 수량. 창고 ID 오름차순"),
                fieldWithPath("products[].warehouses[].warehouseId").type(JsonFieldType.NUMBER).description("창고 ID"),
                fieldWithPath("products[].warehouses[].availableQuantity").type(JsonFieldType.NUMBER).description("해당 창고의 가용 수량"),
            ),
        )

    fun invalid(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "inventory-availability-invalid",
            TAG,
            SUMMARY,
            "조회 조건이 올바르지 않으면 400을 응답한다. 상품이 없거나 100개를 넘거나 창고가 100개를 넘거나 ID가 양수가 아니면 INV_INVALID_AVAILABILITY_QUERY, " +
                "필수 파라미터가 없거나 숫자가 아니면 VALIDATION_FAILED이다.",
            null,
            "Problem",
            ApiDoc.authorization,
            ApiDoc.problem(),
        )
}
