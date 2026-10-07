package com.dozycoffee.inventory.product.application

import com.dozycoffee.inventory.product.application.port.`in`.result.ProductResult
import com.dozycoffee.inventory.product.domain.enumeration.ProductCategory
import com.dozycoffee.inventory.product.domain.enumeration.ProductStatus
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProductResultTest {
    private val result: ProductResult = ProductResult(1L, "BEAN-001", "원두", ProductCategory.BEAN, "KG", 180, ProductStatus.ACTIVE)

    @Test
    fun `활성 상품만 isActive이다`() {
        assertTrue(result.isActive)
        assertFalse(result.copy(productStatus = ProductStatus.INACTIVE).isActive)
    }
}
