package com.dozycoffee.ims.product.adapter.out.persistence

import com.dozycoffee.ims.global.config.ClockConfig
import com.dozycoffee.ims.global.config.R2dbcConfig
import com.dozycoffee.ims.global.security.LocalActorProvider
import com.dozycoffee.ims.product.domain.enumeration.ProductCategory
import com.dozycoffee.ims.product.domain.enumeration.ProductStatus
import com.dozycoffee.ims.product.domain.exception.DuplicateProductCodeException
import com.dozycoffee.ims.product.domain.model.Product
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.r2dbc.test.autoconfigure.DataR2dbcTest
import org.springframework.context.annotation.Import
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles

@DataR2dbcTest
@ActiveProfiles("local")
@Import(ProductPersistenceAdapter::class, R2dbcConfig::class, ClockConfig::class, LocalActorProvider::class)
class ProductPersistenceAdapterTest {
    @Autowired
    private lateinit var adapter: ProductPersistenceAdapter

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    @AfterEach
    fun cleanUp() =
        runBlocking<Unit> {
            databaseClient
                .sql("DELETE FROM product")
                .fetch()
                .rowsUpdated()
                .awaitSingle()
            Unit
        }

    private fun newProduct(
        code: String = "BEAN-001",
        category: ProductCategory = ProductCategory.BEAN,
        shelfLifeDays: Int? = 180,
    ): Product = Product.create(code, "원두 $code", category, "KG", shelfLifeDays)

    @Test
    fun `저장하면 식별자가 부여되고 같은 값으로 조회된다`() =
        runBlocking<Unit> {
            val saved: Product = adapter.save(newProduct())

            val found: Product? = adapter.findById(checkNotNull(saved.productId))
            assertThat(saved.productId).isNotNull()
            assertThat(found).isEqualTo(saved)
            assertThat(found?.productCode).isEqualTo("BEAN-001")
            assertThat(found?.category).isEqualTo(ProductCategory.BEAN)
            assertThat(found?.shelfLifeDays).isEqualTo(180)
            assertThat(found?.productStatus).isEqualTo(ProductStatus.ACTIVE)
        }

    @Test
    fun `유통기한이 없는 상품은 null로 저장된다`() =
        runBlocking<Unit> {
            val saved: Product = adapter.save(newProduct(shelfLifeDays = null))

            assertThat(adapter.findById(checkNotNull(saved.productId))?.shelfLifeDays).isNull()
        }

    @Test
    fun `상품 코드로 조회하고 존재 여부를 확인한다`() =
        runBlocking<Unit> {
            adapter.save(newProduct())

            assertThat(adapter.findByProductCode("BEAN-001")).isNotNull()
            assertThat(adapter.findByProductCode("NONE")).isNull()
            assertThat(adapter.existsByProductCode("BEAN-001")).isTrue()
            assertThat(adapter.existsByProductCode("NONE")).isFalse()
        }

    @Test
    fun `같은 상품 코드를 저장하면 DuplicateProductCodeException으로 변환된다`() =
        runBlocking<Unit> {
            adapter.save(newProduct())

            assertThrows<DuplicateProductCodeException> { adapter.save(newProduct()) }
        }

    @Test
    fun `상태를 바꿔 저장하면 상태와 수정 정보만 바뀌고 생성 정보는 유지된다`() =
        runBlocking<Unit> {
            val saved: Product = adapter.save(newProduct())
            val productId: Long = checkNotNull(saved.productId)
            val createdBefore: Map<String, Any?> = auditColumns(productId)

            val loaded: Product = checkNotNull(adapter.findById(productId))
            loaded.changeStatus(ProductStatus.INACTIVE)
            adapter.save(loaded)

            val after: Map<String, Any?> = auditColumns(productId)
            assertThat(adapter.findById(productId)?.productStatus).isEqualTo(ProductStatus.INACTIVE)
            assertThat(after["created_at"]).isEqualTo(createdBefore["created_at"])
            assertThat(after["created_by"]).isEqualTo(createdBefore["created_by"])
            assertThat(after["updated_by"]).isEqualTo(LocalActorProvider.LOCAL_USER.auditName)
        }

    @Test
    fun `목록은 product_id 오름차순이고 분류와 상태와 코드 필터를 조합한다`() =
        runBlocking<Unit> {
            val bean1: Product = adapter.save(newProduct("BEAN-001", ProductCategory.BEAN))
            adapter.save(newProduct("SYRUP-001", ProductCategory.SYRUP))
            val bean2: Product = adapter.save(newProduct("BEAN-002", ProductCategory.BEAN))
            bean2.changeStatus(ProductStatus.INACTIVE)
            adapter.save(bean2)

            val all: List<Product> = adapter.findAll(null, null, null, 0L, 10)
            val beans: List<Product> = adapter.findAll(null, ProductCategory.BEAN, null, 0L, 10)
            val activeBeans: List<Product> = adapter.findAll(null, ProductCategory.BEAN, ProductStatus.ACTIVE, 0L, 10)
            val byCode: List<Product> = adapter.findAll("BEAN-002", null, null, 0L, 10)

            assertThat(all.map { it.productCode }).containsExactly("BEAN-001", "SYRUP-001", "BEAN-002")
            assertThat(beans.map { it.productCode }).containsExactly("BEAN-001", "BEAN-002")
            assertThat(activeBeans).containsExactly(bean1)
            assertThat(byCode.map { it.productCode }).containsExactly("BEAN-002")
        }

    @Test
    fun `목록은 오프셋과 크기로 자르고 전체 건수는 필터만 반영한다`() =
        runBlocking<Unit> {
            (1..5).forEach { adapter.save(newProduct("BEAN-00$it")) }
            adapter.save(newProduct("SYRUP-001", ProductCategory.SYRUP))

            val page: List<Product> = adapter.findAll(null, ProductCategory.BEAN, null, 2L, 2)

            assertThat(page.map { it.productCode }).containsExactly("BEAN-003", "BEAN-004")
            assertThat(adapter.count(null, ProductCategory.BEAN, null)).isEqualTo(5L)
            assertThat(adapter.count(null, null, null)).isEqualTo(6L)
            assertThat(adapter.count("NONE", null, null)).isEqualTo(0L)
        }

    @Test
    fun `ProductCategory와 DB CHECK 제약의 값이 일치한다`() =
        runBlocking<Unit> {
            assertThat(checkValues("ck_product_category")).containsExactlyInAnyOrderElementsOf(ProductCategory.entries.map { it.name })
        }

    @Test
    fun `ProductStatus와 DB CHECK 제약의 값이 일치한다`() =
        runBlocking<Unit> {
            assertThat(checkValues("ck_product_status")).containsExactlyInAnyOrderElementsOf(ProductStatus.entries.map { it.name })
        }

    @Test
    fun `모든 분류와 상태 값을 DB에 저장할 수 있다`() =
        runBlocking<Unit> {
            ProductCategory.entries.forEachIndexed { index: Int, category: ProductCategory ->
                val saved: Product = adapter.save(newProduct("CODE-$index", category))
                saved.changeStatus(ProductStatus.INACTIVE)
                adapter.save(saved)
            }

            assertThat(adapter.count(null, null, ProductStatus.INACTIVE)).isEqualTo(ProductCategory.entries.size.toLong())
        }

    private suspend fun auditColumns(productId: Long): Map<String, Any?> =
        databaseClient
            .sql("SELECT created_at, created_by, updated_by FROM product WHERE product_id = :id")
            .bind("id", productId)
            .fetch()
            .one()
            .awaitFirst()

    private suspend fun checkValues(constraintName: String): List<String> {
        val clause: String =
            databaseClient
                .sql("SELECT check_clause FROM information_schema.check_constraints WHERE constraint_name = :name")
                .bind("name", constraintName)
                .fetch()
                .one()
                .awaitFirst()["CHECK_CLAUSE"] as String
        // 절은 소문자 컬럼명과 연산자, 대문자 enum 값으로 이루어져 대문자 단어만 값이다
        return Regex("[A-Z][A-Z_]+").findAll(clause).map { it.value }.toList()
    }
}
