package com.dozycoffee.inventory.inventory.adapter.out.persistence

import com.dozycoffee.inventory.global.common.BaseEntity
import com.dozycoffee.inventory.inventory.domain.enumeration.LotStatus
import com.dozycoffee.inventory.inventory.domain.model.Lot
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDate

@Table("lot")
class LotEntity(
    @Id
    @Column("lot_id")
    var lotId: Long? = null,
    var productId: Long,
    var lotNumber: String,
    var manufactureDate: LocalDate?,
    var expirationDate: LocalDate?,
    var lotStatus: LotStatus,
) : BaseEntity() {
    fun toDomain(): Lot =
        Lot.reconstitute(
            lotId = checkNotNull(lotId) { "저장된 Lot은 식별자가 있어야 한다" },
            productId = productId,
            lotNumber = lotNumber,
            manufactureDate = manufactureDate,
            expirationDate = expirationDate,
            lotStatus = lotStatus,
        )

    companion object {
        fun from(lot: Lot): LotEntity =
            LotEntity(
                lotId = lot.lotId,
                productId = lot.productId,
                lotNumber = lot.lotNumber,
                manufactureDate = lot.manufactureDate,
                expirationDate = lot.expirationDate,
                lotStatus = lot.lotStatus,
            )
    }
}
