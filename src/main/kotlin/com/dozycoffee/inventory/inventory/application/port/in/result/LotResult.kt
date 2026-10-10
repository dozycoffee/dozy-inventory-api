package com.dozycoffee.inventory.inventory.application.port.`in`.result

import java.time.LocalDate

data class LotResult(
    val lotId: Long,
    val productId: Long,
    val lotNumber: String,
    val manufactureDate: LocalDate?,
    val expirationDate: LocalDate?,
)
