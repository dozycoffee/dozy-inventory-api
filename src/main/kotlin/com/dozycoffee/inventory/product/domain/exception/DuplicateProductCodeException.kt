package com.dozycoffee.inventory.product.domain.exception

import com.dozycoffee.inventory.global.error.DomainException

class DuplicateProductCodeException : DomainException(ProductErrorCode.DUPLICATE_PRODUCT_CODE)
