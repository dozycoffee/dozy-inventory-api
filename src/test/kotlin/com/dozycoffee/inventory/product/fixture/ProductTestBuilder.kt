package com.dozycoffee.inventory.product.fixture

import com.dozycoffee.inventory.product.domain.enumeration.ProductCategory
import com.dozycoffee.inventory.product.domain.enumeration.ProductStatus
import com.dozycoffee.inventory.product.domain.model.Product

class ProductTestBuilder {
    private var productId: Long = 1L
    private var productCode: String = "BEAN-001"
    private var productName: String = "에티오피아 원두"
    private var category: ProductCategory = ProductCategory.BEAN
    private var unit: String = "KG"
    private var shelfLifeDays: Int? = 180
    private var productStatus: ProductStatus = ProductStatus.ACTIVE

    fun productId(productId: Long): ProductTestBuilder = apply { this.productId = productId }

    fun productCode(productCode: String): ProductTestBuilder = apply { this.productCode = productCode }

    fun productStatus(productStatus: ProductStatus): ProductTestBuilder = apply { this.productStatus = productStatus }

    fun build(): Product = Product.reconstitute(productId, productCode, productName, category, unit, shelfLifeDays, productStatus)
}
