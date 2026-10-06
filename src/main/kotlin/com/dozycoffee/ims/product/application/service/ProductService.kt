package com.dozycoffee.ims.product.application.service

import com.dozycoffee.ims.product.application.port.`in`.ChangeProductStatusUseCase
import com.dozycoffee.ims.product.application.port.`in`.GetProductUseCase
import com.dozycoffee.ims.product.application.port.`in`.ListProductsUseCase
import com.dozycoffee.ims.product.application.port.`in`.RegisterProductUseCase
import com.dozycoffee.ims.product.application.port.`in`.command.ChangeProductStatusCommand
import com.dozycoffee.ims.product.application.port.`in`.command.ListProductsQuery
import com.dozycoffee.ims.product.application.port.`in`.command.RegisterProductCommand
import com.dozycoffee.ims.product.application.port.`in`.result.ProductPageResult
import com.dozycoffee.ims.product.application.port.`in`.result.ProductResult
import com.dozycoffee.ims.product.application.port.out.ProductEvent
import com.dozycoffee.ims.product.application.port.out.ProductEventPublisher
import com.dozycoffee.ims.product.application.port.out.ProductEventType
import com.dozycoffee.ims.product.application.port.out.ProductRepository
import com.dozycoffee.ims.product.domain.exception.DuplicateProductCodeException
import com.dozycoffee.ims.product.domain.exception.ProductNotFoundException
import com.dozycoffee.ims.product.domain.model.Product
import org.springframework.transaction.annotation.Transactional

class ProductService(
    private val productRepository: ProductRepository,
    private val productEventPublisher: ProductEventPublisher,
) : RegisterProductUseCase,
    ChangeProductStatusUseCase,
    GetProductUseCase,
    ListProductsUseCase {
    @Transactional
    override suspend fun register(command: RegisterProductCommand): ProductResult {
        val product: Product =
            Product.create(
                productCode = command.productCode,
                productName = command.productName,
                category = command.category,
                unit = command.unit,
                shelfLifeDays = command.shelfLifeDays,
            )
        // 동시 요청은 이 조회를 함께 통과할 수 있으므로 최종 방어는 uq_product_code 위반 변환(영속성 어댑터)이 맡는다
        if (productRepository.existsByProductCode(product.productCode)) {
            throw DuplicateProductCodeException()
        }

        val result: ProductResult = ProductResult.from(productRepository.save(product))
        productEventPublisher.publish(ProductEvent(ProductEventType.REGISTERED, result))
        return result
    }

    @Transactional
    override suspend fun changeStatus(command: ChangeProductStatusCommand): ProductResult {
        val product: Product = productRepository.findById(command.productId) ?: throw ProductNotFoundException()
        if (!product.changeStatus(command.productStatus)) {
            return ProductResult.from(product)
        }

        val result: ProductResult = ProductResult.from(productRepository.save(product))
        productEventPublisher.publish(ProductEvent(ProductEventType.STATUS_CHANGED, result))
        return result
    }

    @Transactional(readOnly = true)
    override suspend fun getById(productId: Long): ProductResult =
        ProductResult.from(productRepository.findById(productId) ?: throw ProductNotFoundException())

    @Transactional(readOnly = true)
    override suspend fun getByCode(productCode: String): ProductResult =
        ProductResult.from(
            productRepository.findByProductCode(Product.normalizeProductCode(productCode)) ?: throw ProductNotFoundException(),
        )

    @Transactional(readOnly = true)
    override suspend fun list(query: ListProductsQuery): ProductPageResult {
        val items: List<ProductResult> =
            productRepository
                .findAll(query.category, query.productStatus, query.offset, query.size)
                .map { ProductResult.from(it) }
        val totalElements: Long = productRepository.count(query.category, query.productStatus)
        return ProductPageResult(items, query.page, query.size, totalElements)
    }
}
