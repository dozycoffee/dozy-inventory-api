package com.dozycoffee.ims.product.application.service

import com.dozycoffee.ims.global.error.InvalidDomainValueException
import com.dozycoffee.ims.product.application.port.`in`.command.ChangeProductStatusCommand
import com.dozycoffee.ims.product.application.port.`in`.command.ListProductsQuery
import com.dozycoffee.ims.product.application.port.`in`.command.RegisterProductCommand
import com.dozycoffee.ims.product.application.port.`in`.result.ProductPageResult
import com.dozycoffee.ims.product.application.port.`in`.result.ProductResult
import com.dozycoffee.ims.product.application.port.out.ProductEvent
import com.dozycoffee.ims.product.application.port.out.ProductEventPublisher
import com.dozycoffee.ims.product.application.port.out.ProductEventType
import com.dozycoffee.ims.product.application.port.out.ProductRepository
import com.dozycoffee.ims.product.domain.enumeration.ProductCategory
import com.dozycoffee.ims.product.domain.enumeration.ProductStatus
import com.dozycoffee.ims.product.domain.exception.DuplicateProductCodeException
import com.dozycoffee.ims.product.domain.exception.ProductNotFoundException
import com.dozycoffee.ims.product.domain.model.Product
import com.dozycoffee.ims.product.fixture.ProductTestBuilder
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class ProductServiceTest {
    @Mock
    private lateinit var productRepository: ProductRepository

    @Mock
    private lateinit var productEventPublisher: ProductEventPublisher

    private lateinit var service: ProductService

    private val registerCommand: RegisterProductCommand =
        RegisterProductCommand("BEAN-001", "에티오피아 원두", ProductCategory.BEAN, "KG", 180)

    @BeforeEach
    fun setUp() {
        service = ProductService(productRepository, productEventPublisher)
    }

    @Test
    fun `상품 등록 성공 시 저장하고 REGISTERED 이벤트를 발행한다`() =
        runBlocking<Unit> {
            whenever(productRepository.existsByProductCode("BEAN-001")).thenReturn(false)
            whenever(productRepository.save(any())).thenReturn(ProductTestBuilder().build())

            val result: ProductResult = service.register(registerCommand)

            assertEquals(1L, result.productId)
            assertEquals(ProductStatus.ACTIVE, result.productStatus)
            verifyBlocking(productEventPublisher) { publish(ProductEvent(ProductEventType.REGISTERED, result)) }
        }

    @Test
    fun `이미 있는 상품 코드로 등록하면 저장과 이벤트 없이 실패`() =
        runBlocking<Unit> {
            whenever(productRepository.existsByProductCode("BEAN-001")).thenReturn(true)

            assertThrows<DuplicateProductCodeException> { service.register(registerCommand) }

            verifyBlocking(productRepository, never()) { save(any()) }
            verifyBlocking(productEventPublisher, never()) { publish(any()) }
        }

    @Test
    fun `등록 시 정규화한 상품 코드로 중복을 확인한다`() =
        runBlocking<Unit> {
            whenever(productRepository.existsByProductCode("BEAN-001")).thenReturn(true)

            assertThrows<DuplicateProductCodeException> { service.register(registerCommand.copy(productCode = " bean-001 ")) }
        }

    @Test
    fun `잘못된 입력은 중복 조회 전에 도메인 검증에서 실패`() =
        runBlocking<Unit> {
            assertThrows<InvalidDomainValueException> { service.register(registerCommand.copy(productCode = " ")) }

            verifyBlocking(productRepository, never()) { existsByProductCode(any()) }
        }

    @Test
    fun `상태를 변경하면 저장하고 STATUS_CHANGED 이벤트를 발행한다`() =
        runBlocking<Unit> {
            val inactive: Product = ProductTestBuilder().productStatus(ProductStatus.INACTIVE).build()
            whenever(productRepository.findById(1L)).thenReturn(ProductTestBuilder().build())
            whenever(productRepository.save(any())).thenReturn(inactive)

            val result: ProductResult = service.changeStatus(ChangeProductStatusCommand(1L, ProductStatus.INACTIVE))

            assertEquals(ProductStatus.INACTIVE, result.productStatus)
            verifyBlocking(productEventPublisher) { publish(ProductEvent(ProductEventType.STATUS_CHANGED, result)) }
        }

    @Test
    fun `같은 상태로 변경하면 성공하지만 저장과 이벤트는 없다`() =
        runBlocking<Unit> {
            whenever(productRepository.findById(1L)).thenReturn(ProductTestBuilder().build())

            val result: ProductResult = service.changeStatus(ChangeProductStatusCommand(1L, ProductStatus.ACTIVE))

            assertEquals(ProductStatus.ACTIVE, result.productStatus)
            verifyBlocking(productRepository, never()) { save(any()) }
            verifyBlocking(productEventPublisher, never()) { publish(any()) }
        }

    @Test
    fun `없는 상품의 상태를 변경하면 NotFound`() =
        runBlocking<Unit> {
            whenever(productRepository.findById(9L)).thenReturn(null)

            assertThrows<ProductNotFoundException> { service.changeStatus(ChangeProductStatusCommand(9L, ProductStatus.INACTIVE)) }
        }

    @Test
    fun `ID와 코드로 단건 조회한다`() =
        runBlocking<Unit> {
            whenever(productRepository.findById(1L)).thenReturn(ProductTestBuilder().build())
            whenever(productRepository.findByProductCode("BEAN-001")).thenReturn(ProductTestBuilder().build())

            assertEquals("BEAN-001", service.getById(1L).productCode)
            assertEquals(1L, service.getByCode(" bean-001 ").productId)
        }

    @Test
    fun `없는 상품을 조회하면 NotFound`() =
        runBlocking<Unit> {
            whenever(productRepository.findById(9L)).thenReturn(null)
            whenever(productRepository.findByProductCode("NONE")).thenReturn(null)

            assertThrows<ProductNotFoundException> { service.getById(9L) }
            assertThrows<ProductNotFoundException> { service.getByCode("NONE") }
        }

    @Test
    fun `목록 조회는 정규화한 코드와 필터, 오프셋을 전달하고 전체 건수를 함께 반환한다`() =
        runBlocking<Unit> {
            val products: List<Product> = listOf(ProductTestBuilder().build(), ProductTestBuilder().productId(2L).build())
            whenever(productRepository.findAll("BEAN-001", ProductCategory.BEAN, ProductStatus.ACTIVE, 20L, 10)).thenReturn(products)
            whenever(productRepository.count("BEAN-001", ProductCategory.BEAN, ProductStatus.ACTIVE)).thenReturn(25L)

            val result: ProductPageResult =
                service.list(ListProductsQuery(" bean-001 ", ProductCategory.BEAN, ProductStatus.ACTIVE, page = 2, size = 10))

            assertEquals(listOf(1L, 2L), result.items.map { it.productId })
            assertEquals(25L, result.totalElements)
            assertEquals(2, result.page)
            assertEquals(10, result.size)
        }
}
