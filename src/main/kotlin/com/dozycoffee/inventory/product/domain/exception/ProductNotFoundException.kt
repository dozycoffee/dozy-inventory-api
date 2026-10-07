package com.dozycoffee.inventory.product.domain.exception

import com.dozycoffee.inventory.global.error.DomainException

class ProductNotFoundException : DomainException(ProductErrorCode.PRODUCT_NOT_FOUND)
