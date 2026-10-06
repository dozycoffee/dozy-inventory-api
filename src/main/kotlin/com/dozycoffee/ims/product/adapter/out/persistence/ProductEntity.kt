package com.dozycoffee.ims.product.adapter.out.persistence

import com.dozycoffee.ims.global.common.BaseEntity
import com.dozycoffee.ims.product.domain.enumeration.ProductCategory
import com.dozycoffee.ims.product.domain.enumeration.ProductStatus
import com.dozycoffee.ims.product.domain.model.Product
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

@Table("product")
class ProductEntity(
    @Id
    @Column("product_id")
    var productId: Long? = null,
    var productCode: String,
    var productName: String,
    var category: ProductCategory,
    var unit: String,
    var shelfLifeDays: Int?,
    var productStatus: ProductStatus,
) : BaseEntity() {
    fun toDomain(): Product =
        Product.reconstitute(
            productId = checkNotNull(productId) { "저장된 상품은 식별자가 있어야 한다" },
            productCode = productCode,
            productName = productName,
            category = category,
            unit = unit,
            shelfLifeDays = shelfLifeDays,
            productStatus = productStatus,
        )

    companion object {
        fun from(product: Product): ProductEntity =
            ProductEntity(
                productId = product.productId,
                productCode = product.productCode,
                productName = product.productName,
                category = product.category,
                unit = product.unit,
                shelfLifeDays = product.shelfLifeDays,
                productStatus = product.productStatus,
            )
    }
}
