package com.dozycoffee.ims.product.domain.exception

import com.dozycoffee.ims.global.error.DomainException

class DuplicateProductCodeException : DomainException(ProductErrorCode.DUPLICATE_PRODUCT_CODE)
