package com.dozycoffee.ims.product.adapter.`in`.web

import com.dozycoffee.ims.support.ApiDoc
import org.springframework.http.HttpHeaders
import org.springframework.restdocs.headers.HeaderDocumentation.headerWithName
import org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders
import org.springframework.restdocs.payload.FieldDescriptor
import org.springframework.restdocs.payload.JsonFieldType
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.requestFields
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.restdocs.payload.PayloadDocumentation.subsectionWithPath
import org.springframework.restdocs.request.RequestDocumentation.parameterWithName
import org.springframework.restdocs.request.RequestDocumentation.pathParameters
import org.springframework.restdocs.request.RequestDocumentation.queryParameters
import org.springframework.test.web.reactive.server.EntityExchangeResult
import java.util.function.Consumer

/** 상품 API의 명세. `ProductControllerTest`가 `.consumeWith(...)`로 붙여 REST Docs 스니펫을 만든다 */
object ProductApiDocs {
    private val productFields: Array<FieldDescriptor> =
        arrayOf(
            fieldWithPath("productId").type(JsonFieldType.NUMBER).description("상품 ID"),
            fieldWithPath("productCode").type(JsonFieldType.STRING).description("상품 코드"),
            fieldWithPath("productName").type(JsonFieldType.STRING).description("상품명"),
            fieldWithPath("category").type(JsonFieldType.STRING).description("분류: BEAN, SYRUP, POWDER, DAIRY, SUPPLY, MD"),
            fieldWithPath("unit").type(JsonFieldType.STRING).description("단위"),
            fieldWithPath("shelfLifeDays").type(JsonFieldType.NUMBER).optional().description("유통기한 일수. 없으면 유통기한 없는 상품"),
            fieldWithPath("productStatus").type(JsonFieldType.STRING).description("상태: ACTIVE, INACTIVE"),
        )

    fun register(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "product-register",
            "Product",
            "상품 등록",
            "상품 마스터를 등록한다. `ims:admin`만 호출할 수 있다. 상품 코드는 앞뒤 공백을 없애고 대문자로 통일해 저장한다.",
            "RegisterProductRequest",
            "ProductResponse",
            ApiDoc.authorization,
            requestFields(
                fieldWithPath("productCode").type(JsonFieldType.STRING).description("상품 코드. 최대 50자"),
                fieldWithPath("productName").type(JsonFieldType.STRING).description("상품명. 최대 100자"),
                fieldWithPath("category")
                    .type(JsonFieldType.STRING)
                    .description("분류: BEAN, SYRUP, POWDER, DAIRY, SUPPLY, MD"),
                fieldWithPath("unit").type(JsonFieldType.STRING).description("단위. 최대 20자"),
                fieldWithPath("shelfLifeDays")
                    .type(JsonFieldType.NUMBER)
                    .optional()
                    .description("유통기한 일수. 0 이상, 없으면 유통기한 없음"),
            ),
            responseHeaders(headerWithName(HttpHeaders.LOCATION).description("생성된 상품의 경로")),
            responseFields(*productFields),
        )

    fun registerInvalid(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "product-register-invalid",
            "Product",
            "상품 등록",
            "요청 값이 올바르지 않으면 400 VALIDATION_FAILED와 필드별 errors를 응답한다.",
            null,
            "Problem",
            ApiDoc.authorization,
            ApiDoc.problem(withErrors = true),
        )

    fun registerDuplicate(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "product-register-duplicate",
            "Product",
            "상품 등록",
            "이미 있는 상품 코드이면 409 IMS_DUPLICATE_PRODUCT_CODE를 응답한다.",
            null,
            "Problem",
            ApiDoc.authorization,
            ApiDoc.problem(),
        )

    fun changeStatus(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "product-change-status",
            "Product",
            "상품 상태 변경",
            "상품을 ACTIVE 또는 INACTIVE로 바꾼다. `ims:admin`만 호출할 수 있다. 이미 같은 상태이면 변경 없이 현재 상품을 응답한다.",
            "ChangeProductStatusRequest",
            "ProductResponse",
            ApiDoc.authorization,
            pathParameters(parameterWithName("productId").description("상품 ID")),
            requestFields(
                fieldWithPath("productStatus").type(JsonFieldType.STRING).description("바꿀 상태: ACTIVE, INACTIVE"),
            ),
            responseFields(*productFields),
        )

    fun get(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "product-get",
            "Product",
            "상품 단건 조회",
            "상품 ID로 상품을 조회한다. `ims:service`, `ims:warehouse_manager`, `ims:admin` 모두 호출할 수 있다.",
            null,
            "ProductResponse",
            ApiDoc.authorization,
            pathParameters(parameterWithName("productId").description("상품 ID")),
            responseFields(*productFields),
        )

    fun getNotFound(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "product-get-not-found",
            "Product",
            "상품 단건 조회",
            "없는 상품이면 404 IMS_PRODUCT_NOT_FOUND를 응답한다.",
            null,
            "Problem",
            ApiDoc.authorization,
            pathParameters(parameterWithName("productId").description("상품 ID")),
            ApiDoc.problem(),
        )

    fun list(): Consumer<EntityExchangeResult<ByteArray>> =
        ApiDoc.operation(
            "product-list",
            "Product",
            "상품 목록 조회",
            "상품을 `product_id` 오름차순으로 조회한다. 모든 조건은 선택이고 함께 쓰면 AND로 적용한다. 코드는 앞뒤 공백을 없애고 대문자로 통일해 비교한다.",
            null,
            "ProductPageResponse",
            ApiDoc.authorization,
            queryParameters(
                parameterWithName("code").optional().description("상품 코드"),
                parameterWithName("category").optional().description("분류: BEAN, SYRUP, POWDER, DAIRY, SUPPLY, MD"),
                parameterWithName("status").optional().description("상태: ACTIVE, INACTIVE"),
                parameterWithName("page").optional().description("페이지 번호. 0부터 시작, 기본 0"),
                parameterWithName("size").optional().description("페이지 크기. 1~100, 기본 20"),
            ),
            responseFields(
                subsectionWithPath("items").type(JsonFieldType.ARRAY).description("상품 목록(상품 단건 조회 응답과 같은 형식)"),
                fieldWithPath("page").type(JsonFieldType.NUMBER).description("요청한 페이지 번호"),
                fieldWithPath("size").type(JsonFieldType.NUMBER).description("요청한 페이지 크기"),
                fieldWithPath("totalElements").type(JsonFieldType.NUMBER).description("조건에 맞는 전체 상품 수"),
            ),
        )
}
