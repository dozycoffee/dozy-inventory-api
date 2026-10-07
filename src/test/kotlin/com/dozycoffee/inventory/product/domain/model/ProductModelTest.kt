package com.dozycoffee.inventory.product.domain.model

import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.product.domain.enumeration.ProductCategory
import com.dozycoffee.inventory.product.domain.enumeration.ProductStatus
import com.dozycoffee.inventory.product.domain.exception.ProductErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ProductModelTest {
    private fun create(
        productCode: String? = "BEAN-001",
        productName: String? = "에티오피아 원두",
        category: ProductCategory? = ProductCategory.BEAN,
        unit: String? = "KG",
        shelfLifeDays: Int? = 180,
    ): Product = Product.create(productCode, productName, category, unit, shelfLifeDays)

    @Test
    fun `상품 생성 시 ACTIVE 상태이고 식별자는 없다`() {
        val product: Product = create()

        assertNull(product.productId)
        assertEquals(ProductStatus.ACTIVE, product.productStatus)
        assertEquals("BEAN-001", product.productCode)
    }

    @Test
    fun `유통기한 일수는 없어도 되고 0은 허용한다`() {
        assertNull(create(shelfLifeDays = null).shelfLifeDays)
        assertEquals(0, create(shelfLifeDays = 0).shelfLifeDays)
    }

    @Test
    fun `상품 코드가 null이거나 공백이면 생성 실패`() {
        listOf(null, "", "  ").forEach { code: String? ->
            val e: InvalidDomainValueException = assertThrows { create(productCode = code) }
            assertEquals(ProductErrorCode.INVALID_PRODUCT_CODE.code, e.errorCode.code)
        }
    }

    @Test
    fun `상품명이 공백이면 생성 실패`() {
        val e: InvalidDomainValueException = assertThrows { create(productName = " ") }
        assertEquals(ProductErrorCode.INVALID_PRODUCT_NAME.code, e.errorCode.code)
    }

    @Test
    fun `분류가 null이면 생성 실패`() {
        val e: InvalidDomainValueException = assertThrows { create(category = null) }
        assertEquals(ProductErrorCode.INVALID_CATEGORY.code, e.errorCode.code)
    }

    @Test
    fun `단위가 공백이면 생성 실패`() {
        val e: InvalidDomainValueException = assertThrows { create(unit = "") }
        assertEquals(ProductErrorCode.INVALID_UNIT.code, e.errorCode.code)
    }

    @Test
    fun `유통기한 일수가 음수이면 생성 실패`() {
        val e: InvalidDomainValueException = assertThrows { create(shelfLifeDays = -1) }
        assertEquals(ProductErrorCode.INVALID_SHELF_LIFE_DAYS.code, e.errorCode.code)
    }

    @Test
    fun `복원한 상품은 저장된 값을 그대로 가지고 식별자로 동등성을 판단한다`() {
        val a: Product = Product.reconstitute(1L, "BEAN-001", "원두", ProductCategory.BEAN, "KG", null, ProductStatus.INACTIVE)
        val b: Product = Product.reconstitute(1L, "BEAN-002", "다른 원두", ProductCategory.MD, "EA", 10, ProductStatus.ACTIVE)
        val c: Product = Product.reconstitute(2L, "BEAN-001", "원두", ProductCategory.BEAN, "KG", null, ProductStatus.INACTIVE)

        assertEquals(ProductStatus.INACTIVE, a.productStatus)
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun `상태를 바꾸면 true를 반환하고 같은 상태로 바꾸면 false를 반환한다`() {
        val product: Product = create()

        assertEquals(true, product.changeStatus(ProductStatus.INACTIVE))
        assertEquals(ProductStatus.INACTIVE, product.productStatus)
        assertEquals(false, product.changeStatus(ProductStatus.INACTIVE))
        assertEquals(true, product.changeStatus(ProductStatus.ACTIVE))
        assertEquals(ProductStatus.ACTIVE, product.productStatus)
    }

    @Test
    fun `상품 코드는 앞뒤 공백을 없애고 대문자로 통일한다`() {
        assertEquals("BEAN-001", create(productCode = "  bean-001 ").productCode)
        assertEquals("BEAN-001", Product.normalizeProductCode(" Bean-001"))
    }
}
