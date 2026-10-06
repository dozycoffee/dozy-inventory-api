package com.dozycoffee.ims.product.domain.exception

import com.dozycoffee.ims.global.error.DomainException

class ProductNotFoundException : DomainException(ProductErrorCode.PRODUCT_NOT_FOUND)
