package com.dozycoffee.inventory.product.adapter.out.persistence

import com.dozycoffee.inventory.global.config.ClockConfig
import com.dozycoffee.inventory.global.config.R2dbcConfig
import com.dozycoffee.inventory.global.security.LocalActorProvider
import com.dozycoffee.inventory.product.domain.enumeration.ProductCategory
import com.dozycoffee.inventory.product.domain.enumeration.ProductStatus
import com.dozycoffee.inventory.product.domain.exception.DuplicateProductCodeException
import com.dozycoffee.inventory.product.domain.model.Product
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
    fun `상품 ID 여러 개를 한 번에 조회하고 없는 ID는 결과에 없으며 빈 집합은 조회하지 않는다`() =
        runBlocking<Unit> {
            val a: Long = checkNotNull(adapter.save(newProduct("BEAN-001")).productId)
            val b: Long = checkNotNull(adapter.save(newProduct("BEAN-002")).productId)
            adapter.save(newProduct("BEAN-003"))

            val found: List<Product> = adapter.findAllByIds(setOf(a, b, b + 1000))

            assertThat(found.map { it.productId }).containsExactlyInAnyOrder(a, b)
            assertThat(found.map { it.productCode }).containsExactlyInAnyOrder("BEAN-001", "BEAN-002")
            assertThat(adapter.findAllByIds(emptySet())).isEmpty()
        }

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

            val all: List<Product> = adapter.findAll(null, null, null, null, 0L, 10)
            val beans: List<Product> = adapter.findAll(null, null, ProductCategory.BEAN, null, 0L, 10)
            val activeBeans: List<Product> = adapter.findAll(null, null, ProductCategory.BEAN, ProductStatus.ACTIVE, 0L, 10)
            val byCode: List<Product> = adapter.findAll("BEAN-002", null, null, null, 0L, 10)

            assertThat(all.map { it.productCode }).containsExactly("BEAN-001", "SYRUP-001", "BEAN-002")
            assertThat(beans.map { it.productCode }).containsExactly("BEAN-001", "BEAN-002")
            assertThat(activeBeans).containsExactly(bean1)
            assertThat(byCode.map { it.productCode }).containsExactly("BEAN-002")
        }

    private fun named(
        code: String,
        name: String,
        category: ProductCategory = ProductCategory.BEAN,
    ): Product = Product.create(code, name, category, "KG", null)

    @Test
    fun `검색어는 코드나 이름의 일부와 대소문자 무시로 일치하고 상품 ID 순서를 지킨다`() =
        runBlocking<Unit> {
            adapter.save(named("BEAN-ETH", "에티오피아 예가체프 원두"))
            adapter.save(named("SYR-VAN", "바닐라 시럽", ProductCategory.SYRUP))
            adapter.save(named("DAIRY-MLK", "흰 우유 1L", ProductCategory.DAIRY))

            assertThat(adapter.findAll(null, "원두", null, null, 0L, 10).map { it.productCode }).containsExactly("BEAN-ETH")
            assertThat(adapter.findAll(null, "시럽", null, null, 0L, 10).map { it.productCode }).containsExactly("SYR-VAN")
            assertThat(adapter.findAll(null, "eth", null, null, 0L, 10).map { it.productCode }).containsExactly("BEAN-ETH")
            assertThat(adapter.findAll(null, "Mlk", null, null, 0L, 10).map { it.productCode }).containsExactly("DAIRY-MLK")
            assertThat(
                adapter.findAll(null, "-", null, null, 0L, 10).map { it.productCode },
            ).containsExactly("BEAN-ETH", "SYR-VAN", "DAIRY-MLK")
            assertThat(adapter.findAll(null, "없는말", null, null, 0L, 10)).isEmpty()
            assertThat(adapter.count(null, "-", null, null)).isEqualTo(3L)
            assertThat(adapter.count(null, "원두", null, null)).isEqualTo(1L)
            assertThat(adapter.count(null, "없는말", null, null)).isEqualTo(0L)
        }

    @Test
    fun `검색어는 다른 조건과 AND로 묶이고 코드 또는 이름 어느 쪽이든 일치하면 찾는다`() =
        runBlocking<Unit> {
            adapter.save(named("BEAN-ETH", "에티오피아 원두"))
            adapter.save(named("BEAN-BRA", "브라질 원두"))
            adapter.save(named("SYR-BEAN", "원두 시럽", ProductCategory.SYRUP))
            val inactive: Product = adapter.save(named("BEAN-OLD", "오래된 원두"))
            inactive.changeStatus(ProductStatus.INACTIVE)
            adapter.save(inactive)

            val beanCategory = adapter.findAll(null, "원두", ProductCategory.BEAN, null, 0L, 10).map { it.productCode }
            val byCodeOrName = adapter.findAll(null, "bean", null, null, 0L, 10).map { it.productCode }
            val activeOnly = adapter.findAll(null, "원두", ProductCategory.BEAN, ProductStatus.ACTIVE, 0L, 10).map { it.productCode }
            val withExactCode = adapter.findAll("BEAN-BRA", "원두", null, null, 0L, 10).map { it.productCode }

            assertThat(beanCategory).containsExactly("BEAN-ETH", "BEAN-BRA", "BEAN-OLD")
            assertThat(byCodeOrName).containsExactly("BEAN-ETH", "BEAN-BRA", "SYR-BEAN", "BEAN-OLD")
            assertThat(activeOnly).containsExactly("BEAN-ETH", "BEAN-BRA")
            assertThat(withExactCode).containsExactly("BEAN-BRA")
            assertThat(adapter.count(null, "원두", ProductCategory.BEAN, ProductStatus.ACTIVE)).isEqualTo(2L)
        }

    @Test
    fun `검색어의 퍼센트와 밑줄과 역슬래시는 와일드카드가 아니라 글자 그대로 일치한다`() =
        runBlocking<Unit> {
            adapter.save(named("A-100", "할인 100% 원두"))
            adapter.save(named("A_101", "밑줄_원두"))
            adapter.save(named("A-102", "역슬래시\\원두"))
            adapter.save(named("PLAIN-1", "일반 원두"))

            assertThat(adapter.findAll(null, "%", null, null, 0L, 10).map { it.productCode }).containsExactly("A-100")
            assertThat(adapter.findAll(null, "_", null, null, 0L, 10).map { it.productCode }).containsExactly("A_101")
            assertThat(adapter.findAll(null, "\\", null, null, 0L, 10).map { it.productCode }).containsExactly("A-102")
            assertThat(adapter.count(null, "%", null, null)).isEqualTo(1L)
            assertThat(adapter.findAll(null, "100%", null, null, 0L, 10).map { it.productCode }).containsExactly("A-100")
        }

    @Test
    fun `검색어로 좁힌 목록도 오프셋과 크기로 자른다`() =
        runBlocking<Unit> {
            (1..5).forEach { adapter.save(named("BEAN-00$it", "원두 $it")) }
            adapter.save(named("SYR-001", "시럽", ProductCategory.SYRUP))

            val page = adapter.findAll(null, "원두", null, null, 1L, 2).map { it.productCode }

            assertThat(page).containsExactly("BEAN-002", "BEAN-003")
            assertThat(adapter.count(null, "원두", null, null)).isEqualTo(5L)
        }

    @Test
    fun `목록은 오프셋과 크기로 자르고 전체 건수는 필터만 반영한다`() =
        runBlocking<Unit> {
            (1..5).forEach { adapter.save(newProduct("BEAN-00$it")) }
            adapter.save(newProduct("SYRUP-001", ProductCategory.SYRUP))

            val page: List<Product> = adapter.findAll(null, null, ProductCategory.BEAN, null, 2L, 2)

            assertThat(page.map { it.productCode }).containsExactly("BEAN-003", "BEAN-004")
            assertThat(adapter.count(null, null, ProductCategory.BEAN, null)).isEqualTo(5L)
            assertThat(adapter.count(null, null, null, null)).isEqualTo(6L)
            assertThat(adapter.count("NONE", null, null, null)).isEqualTo(0L)
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

            assertThat(adapter.count(null, null, null, ProductStatus.INACTIVE)).isEqualTo(ProductCategory.entries.size.toLong())
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
