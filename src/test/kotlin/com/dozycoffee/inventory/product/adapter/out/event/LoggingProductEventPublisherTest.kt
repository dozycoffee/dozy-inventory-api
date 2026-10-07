package com.dozycoffee.inventory.product.adapter.out.event

import com.dozycoffee.inventory.product.application.port.`in`.result.ProductResult
import com.dozycoffee.inventory.product.application.port.out.ProductEvent
import com.dozycoffee.inventory.product.application.port.out.ProductEventType
import com.dozycoffee.inventory.product.domain.enumeration.ProductCategory
import com.dozycoffee.inventory.product.domain.enumeration.ProductStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class LoggingProductEventPublisherTest {
    @Test
    fun `이벤트를 발행해도 예외 없이 끝난다`() =
        runBlocking<Unit> {
            val result: ProductResult = ProductResult(1L, "BEAN-001", "원두", ProductCategory.BEAN, "KG", null, ProductStatus.ACTIVE)

            LoggingProductEventPublisher().publish(ProductEvent(ProductEventType.REGISTERED, result))
        }
}
