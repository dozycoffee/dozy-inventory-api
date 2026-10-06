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
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.WebTestClient.RequestBodySpec

@WebFluxTest(ProductController::class)
@Import(SecurityConfig::class, SecurityContextActorProvider::class)
class ProductControllerTest {
    @Autowired
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
            .uri("/api/v1/products/7/status")
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
            verifyBlocking(changeProductStatusUseCase) { changeStatus(ChangeProductStatusCommand(7L, ProductStatus.INACTIVE)) }
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
                .uri("/api/v1/products/9")
                .headers { it.setBearerAuth(bearer("admin")) }
                .exchange()
                .expectStatus()
                .isNotFound
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("IMS_PRODUCT_NOT_FOUND")
        }

    @Test
    fun `목록 조회는 쿼리 파라미터를 Query로 넘기고 페이지 응답을 준다`() =
        runBlocking<Unit> {
            whenever(listProductsUseCase.list(any())).thenReturn(ProductPageResult(listOf(result), 1, 10, 11L))

            webTestClient
                .get()
                .uri("/api/v1/products?code=bean-001&category=BEAN&status=ACTIVE&page=1&size=10")
                .headers { it.setBearerAuth(bearer("service")) }
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
