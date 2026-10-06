package com.dozycoffee.ims.product.adapter.`in`.web

import com.dozycoffee.auth.test.DozyTestTokens
import com.dozycoffee.ims.global.security.SecurityConfig
import com.dozycoffee.ims.global.security.SecurityContextActorProvider
import com.dozycoffee.ims.product.application.port.`in`.ChangeProductStatusUseCase
import com.dozycoffee.ims.product.application.port.`in`.GetProductUseCase
import com.dozycoffee.ims.product.application.port.`in`.ListProductsUseCase
import com.dozycoffee.ims.product.application.port.`in`.RegisterProductUseCase
import com.dozycoffee.ims.product.application.port.`in`.command.ChangeProductStatusCommand
import com.dozycoffee.ims.product.application.port.`in`.command.ListProductsQuery
import com.dozycoffee.ims.product.application.port.`in`.command.RegisterProductCommand
import com.dozycoffee.ims.product.application.port.`in`.result.ProductPageResult
import com.dozycoffee.ims.product.application.port.`in`.result.ProductResult
import com.dozycoffee.ims.product.domain.enumeration.ProductCategory
import com.dozycoffee.ims.product.domain.enumeration.ProductStatus
import com.dozycoffee.ims.product.domain.exception.DuplicateProductCodeException
import com.dozycoffee.ims.product.domain.exception.ProductNotFoundException
import com.dozycoffee.ims.support.ApiDoc
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.kotlin.any
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.restdocs.RestDocumentationContextProvider
import org.springframework.restdocs.RestDocumentationExtension
import org.springframework.restdocs.headers.HeaderDocumentation.headerWithName
import org.springframework.restdocs.headers.HeaderDocumentation.responseHeaders
import org.springframework.restdocs.payload.JsonFieldType
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.requestFields
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.restdocs.payload.PayloadDocumentation.subsectionWithPath
import org.springframework.restdocs.request.RequestDocumentation.parameterWithName
import org.springframework.restdocs.request.RequestDocumentation.pathParameters
import org.springframework.restdocs.request.RequestDocumentation.queryParameters
import org.springframework.restdocs.webtestclient.WebTestClientRestDocumentation.documentationConfiguration
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient

@WebFluxTest(ProductController::class)
@Import(SecurityConfig::class, SecurityContextActorProvider::class)
@ExtendWith(RestDocumentationExtension::class)
class ProductControllerTest {
    @Autowired
    private lateinit var baseClient: WebTestClient

    private lateinit var webTestClient: WebTestClient

    @Autowired
    private lateinit var tokens: DozyTestTokens

    @MockitoBean
    private lateinit var registerProductUseCase: RegisterProductUseCase

    @MockitoBean
    private lateinit var changeProductStatusUseCase: ChangeProductStatusUseCase

    @MockitoBean
    private lateinit var getProductUseCase: GetProductUseCase

    @MockitoBean
    private lateinit var listProductsUseCase: ListProductsUseCase

    private val result: ProductResult = ProductResult(7L, "BEAN-001", "에티오피아 원두", ProductCategory.BEAN, "KG", 180, ProductStatus.ACTIVE)

    private val registerBody: String =
        """{"productCode":"BEAN-001","productName":"에티오피아 원두","category":"BEAN","unit":"KG","shelfLifeDays":180}"""

    @BeforeEach
    fun setUp(restDocumentation: RestDocumentationContextProvider) {
        webTestClient = baseClient.mutate().filter(documentationConfiguration(restDocumentation)).build()
    }

    private val productFields =
        arrayOf(
            fieldWithPath("productId").type(JsonFieldType.NUMBER).description("상품 ID"),
            fieldWithPath("productCode").type(JsonFieldType.STRING).description("상품 코드"),
            fieldWithPath("productName").type(JsonFieldType.STRING).description("상품명"),
            fieldWithPath("category").type(JsonFieldType.STRING).description("분류: BEAN, SYRUP, POWDER, DAIRY, SUPPLY, MD"),
            fieldWithPath("unit").type(JsonFieldType.STRING).description("단위"),
            fieldWithPath("shelfLifeDays").type(JsonFieldType.NUMBER).optional().description("유통기한 일수. 없으면 유통기한 없는 상품"),
            fieldWithPath("productStatus").type(JsonFieldType.STRING).description("상태: ACTIVE, INACTIVE"),
        )

    private fun bearer(role: String): String = tokens.issue(roles = listOf("ims:$role"))

    private fun post(
        role: String?,
        body: String,
    ): WebTestClient.ResponseSpec =
        webTestClient
            .post()
            .uri("/api/v1/products")
            .headers { headers: HttpHeaders -> role?.let { headers.setBearerAuth(bearer(it)) } }
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()

    @Test
    fun `admin이 상품을 등록하면 201과 Location을 응답한다`() =
        runBlocking<Unit> {
            whenever(registerProductUseCase.register(any())).thenReturn(result)

            post("admin", registerBody)
                .expectStatus()
                .isCreated
                .expectHeader()
                .valueEquals(HttpHeaders.LOCATION, "/api/v1/products/7")
                .expectBody()
                .jsonPath("$.productId")
                .isEqualTo(7)
                .jsonPath("$.productCode")
                .isEqualTo("BEAN-001")
                .jsonPath("$.category")
                .isEqualTo("BEAN")
                .jsonPath("$.productStatus")
                .isEqualTo("ACTIVE")
                .consumeWith(
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
                            fieldWithPath("category").type(JsonFieldType.STRING).description("분류: BEAN, SYRUP, POWDER, DAIRY, SUPPLY, MD"),
                            fieldWithPath("unit").type(JsonFieldType.STRING).description("단위. 최대 20자"),
                            fieldWithPath("shelfLifeDays").type(JsonFieldType.NUMBER).optional().description("유통기한 일수. 0 이상, 없으면 유통기한 없음"),
                        ),
                        responseHeaders(headerWithName(HttpHeaders.LOCATION).description("생성된 상품의 경로")),
                        responseFields(*productFields),
                    ),
                )
            verifyBlocking(registerProductUseCase) {
                register(RegisterProductCommand("BEAN-001", "에티오피아 원두", ProductCategory.BEAN, "KG", 180))
            }
        }

    @Test
    fun `등록 요청의 값이 잘못되면 400 VALIDATION_FAILED와 필드별 errors를 응답한다`() {
        post("admin", """{"productCode":" ","productName":"원두","category":"BEAN","unit":"KG","shelfLifeDays":-1}""")
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.code")
            .isEqualTo("VALIDATION_FAILED")
            .jsonPath("$.errors.length()")
            .isEqualTo(2)
            .consumeWith(
                ApiDoc.operation(
                    "product-register-invalid",
                    "Product",
                    "상품 등록",
                    "요청 값이 올바르지 않으면 400 VALIDATION_FAILED와 필드별 errors를 응답한다.",
                    null,
                    "Problem",
                    ApiDoc.authorization,
                    ApiDoc.problem(withErrors = true),
                ),
            )
    }

    @Test
    fun `알 수 없는 분류 값은 400`() {
        post("admin", """{"productCode":"A","productName":"원두","category":"UNKNOWN","unit":"KG"}""")
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `이미 있는 상품 코드는 409 IMS_DUPLICATE_PRODUCT_CODE`() =
        runBlocking<Unit> {
            whenever(registerProductUseCase.register(any())).thenThrow(DuplicateProductCodeException())

            post("admin", registerBody)
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("IMS_DUPLICATE_PRODUCT_CODE")
                .consumeWith(
                    ApiDoc.operation(
                        "product-register-duplicate",
                        "Product",
                        "상품 등록",
                        "이미 있는 상품 코드이면 409 IMS_DUPLICATE_PRODUCT_CODE를 응답한다.",
                        null,
                        "Problem",
                        ApiDoc.authorization,
                        ApiDoc.problem(),
                    ),
                )
        }

    @Test
    fun `admin이 아니면 등록과 상태 변경은 403이고 토큰이 없으면 401`() {
        post("service", registerBody).expectStatus().isForbidden
        post("warehouse_manager", registerBody).expectStatus().isForbidden
        post(null, registerBody).expectStatus().isUnauthorized
        patchStatus("warehouse_manager").expectStatus().isForbidden
    }

    private fun patchStatus(role: String): WebTestClient.ResponseSpec =
        webTestClient
            .patch()
            .uri("/api/v1/products/{productId}/status", 7)
            .headers { headers: HttpHeaders -> headers.setBearerAuth(bearer(role)) }
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"productStatus":"INACTIVE"}""")
            .exchange()

    @Test
    fun `admin이 상태를 변경하면 변경된 상품을 응답한다`() =
        runBlocking<Unit> {
            whenever(changeProductStatusUseCase.changeStatus(any())).thenReturn(result.copy(productStatus = ProductStatus.INACTIVE))

            patchStatus("admin")
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.productStatus")
                .isEqualTo("INACTIVE")
                .consumeWith(
                    ApiDoc.operation(
                        "product-change-status",
                        "Product",
                        "상품 상태 변경",
                        "상품을 ACTIVE 또는 INACTIVE로 바꾼다. `ims:admin`만 호출할 수 있다. 이미 같은 상태이면 변경 없이 현재 상품을 응답한다.",
                        "ChangeProductStatusRequest",
                        "ProductResponse",
                        ApiDoc.authorization,
                        pathParameters(parameterWithName("productId").description("상품 ID")),
                        requestFields(fieldWithPath("productStatus").type(JsonFieldType.STRING).description("바꿀 상태: ACTIVE, INACTIVE")),
                        responseFields(*productFields),
                    ),
                )
            verifyBlocking(changeProductStatusUseCase) { changeStatus(ChangeProductStatusCommand(7L, ProductStatus.INACTIVE)) }
        }

    @Test
    fun `상품 단건 조회 응답을 문서화한다`() =
        runBlocking<Unit> {
            whenever(getProductUseCase.getById(7L)).thenReturn(result)

            webTestClient
                .get()
                .uri("/api/v1/products/{productId}", 7)
                .headers { it.setBearerAuth(bearer("service")) }
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .consumeWith(
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
                    ),
                )
        }

    @Test
    fun `세 role 모두 상품을 조회할 수 있다`() =
        runBlocking<Unit> {
            whenever(getProductUseCase.getById(7L)).thenReturn(result)

            listOf("service", "warehouse_manager", "admin").forEach { role: String ->
                webTestClient
                    .get()
                    .uri("/api/v1/products/7")
                    .headers { it.setBearerAuth(bearer(role)) }
                    .exchange()
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.productCode")
                    .isEqualTo("BEAN-001")
            }
        }

    @Test
    fun `없는 상품은 404 IMS_PRODUCT_NOT_FOUND`() =
        runBlocking<Unit> {
            whenever(getProductUseCase.getById(9L)).thenThrow(ProductNotFoundException())

            webTestClient
                .get()
                .uri("/api/v1/products/{productId}", 9)
                .headers { it.setBearerAuth(bearer("admin")) }
                .exchange()
                .expectStatus()
                .isNotFound
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("IMS_PRODUCT_NOT_FOUND")
                .consumeWith(
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
                    ),
                )
        }

    @Test
    fun `목록 조회는 쿼리 파라미터를 Query로 넘기고 페이지 응답을 준다`() =
        runBlocking<Unit> {
            whenever(listProductsUseCase.list(any())).thenReturn(ProductPageResult(listOf(result), 1, 10, 11L))

            webTestClient
                .get()
                .uri(
                    "/api/v1/products?code={code}&category={category}&status={status}&page={page}&size={size}",
                    "bean-001",
                    "BEAN",
                    "ACTIVE",
                    1,
                    10,
                ).headers { it.setBearerAuth(bearer("service")) }
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.items[0].productId")
                .isEqualTo(7)
                .jsonPath("$.page")
                .isEqualTo(1)
                .jsonPath("$.size")
                .isEqualTo(10)
                .jsonPath("$.totalElements")
                .isEqualTo(11)
                .consumeWith(
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
                    ),
                )
            verifyBlocking(listProductsUseCase) { list(ListProductsQuery("bean-001", ProductCategory.BEAN, ProductStatus.ACTIVE, 1, 10)) }
        }

    @Test
    fun `목록 조회의 페이지 크기가 범위를 벗어나면 400`() {
        webTestClient
            .get()
            .uri("/api/v1/products?size=101")
            .headers { it.setBearerAuth(bearer("admin")) }
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.code")
            .isEqualTo("IMS_INVALID_PAGE_REQUEST")
    }
}
