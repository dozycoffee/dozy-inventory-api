package com.dozycoffee.ims.product.adapter.`in`.web

import com.dozycoffee.ims.global.security.ImsAuthorize
import com.dozycoffee.ims.product.adapter.`in`.web.request.ChangeProductStatusRequest
import com.dozycoffee.ims.product.adapter.`in`.web.request.RegisterProductRequest
import com.dozycoffee.ims.product.adapter.`in`.web.response.ProductPageResponse
import com.dozycoffee.ims.product.adapter.`in`.web.response.ProductResponse
import com.dozycoffee.ims.product.application.port.`in`.ChangeProductStatusUseCase
import com.dozycoffee.ims.product.application.port.`in`.GetProductUseCase
import com.dozycoffee.ims.product.application.port.`in`.ListProductsUseCase
import com.dozycoffee.ims.product.application.port.`in`.RegisterProductUseCase
import com.dozycoffee.ims.product.application.port.`in`.command.ListProductsQuery
import com.dozycoffee.ims.product.application.port.`in`.result.ProductResult
import com.dozycoffee.ims.product.domain.enumeration.ProductCategory
import com.dozycoffee.ims.product.domain.enumeration.ProductStatus
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@RestController
@RequestMapping("/api/v1/products")
class ProductController(
    private val registerProductUseCase: RegisterProductUseCase,
    private val changeProductStatusUseCase: ChangeProductStatusUseCase,
    private val getProductUseCase: GetProductUseCase,
    private val listProductsUseCase: ListProductsUseCase,
) {
    @PreAuthorize(ImsAuthorize.ADMIN)
    @PostMapping
    suspend fun register(
        @Valid @RequestBody request: RegisterProductRequest,
    ): ResponseEntity<ProductResponse> {
        val result: ProductResult = registerProductUseCase.register(request.toCommand())
        return ResponseEntity.created(URI.create("/api/v1/products/${result.productId}")).body(ProductResponse.from(result))
    }

    @PreAuthorize(ImsAuthorize.ADMIN)
    @PatchMapping("/{productId}/status")
    suspend fun changeStatus(
        @PathVariable productId: Long,
        @Valid @RequestBody request: ChangeProductStatusRequest,
    ): ProductResponse = ProductResponse.from(changeProductStatusUseCase.changeStatus(request.toCommand(productId)))

    @PreAuthorize(ImsAuthorize.ANY)
    @GetMapping("/{productId}")
    suspend fun get(
        @PathVariable productId: Long,
    ): ProductResponse = ProductResponse.from(getProductUseCase.getById(productId))

    @PreAuthorize(ImsAuthorize.ANY)
    @GetMapping
    suspend fun list(
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) category: ProductCategory?,
        @RequestParam(required = false) status: ProductStatus?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ProductPageResponse = ProductPageResponse.from(listProductsUseCase.list(ListProductsQuery(code, category, status, page, size)))
}
