package com.dozycoffee.inventory.product.adapter.out.event

import com.dozycoffee.inventory.product.application.port.out.ProductEvent
import com.dozycoffee.inventory.product.application.port.out.ProductEventPublisher
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** 이벤트를 저장하거나 전달하지 않고 로그만 남긴다. 구독자에게는 아무것도 발행되지 않는다 */
@Component
class LoggingProductEventPublisher : ProductEventPublisher {
    override suspend fun publish(event: ProductEvent) {
        log.info("Product event: type={}, productId={}", event.eventType, event.product.productId)
    }

    private companion object {
        private val log: Logger = LoggerFactory.getLogger(LoggingProductEventPublisher::class.java)
    }
}
