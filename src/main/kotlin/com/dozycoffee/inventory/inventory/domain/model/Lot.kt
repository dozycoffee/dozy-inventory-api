package com.dozycoffee.inventory.inventory.domain.model

import com.dozycoffee.inventory.global.error.DomainValidator
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.inventory.domain.enumeration.LotStatus
import com.dozycoffee.inventory.inventory.domain.exception.InventoryErrorCode
import java.time.LocalDate

/** 공급사가 부여한 Lot 번호 단위. 번호·제조일자·유통기한은 공급사 값이며 inventory 서비스가 바꾸거나 만들지 않는다 */
class Lot private constructor(
    val lotId: Long?,
    val productId: Long,
    val lotNumber: String,
    val manufactureDate: LocalDate?,
    val expirationDate: LocalDate?,
    lotStatus: LotStatus,
) {
    var lotStatus: LotStatus = lotStatus
        private set

    /** [today] 기준의 Lot 상태. 유통기한이 오늘 이하이면 경과, [expiringSoonDays]일 이내이면 임박이다 */
    fun statusAt(
        today: LocalDate,
        expiringSoonDays: Int,
    ): LotStatus = evaluateStatus(expirationDate, today, expiringSoonDays)

    /** 상태가 실제로 바뀌었으면 true를 반환한다 */
    fun refreshStatus(
        today: LocalDate,
        expiringSoonDays: Int,
    ): Boolean {
        val evaluated: LotStatus = statusAt(today, expiringSoonDays)
        if (evaluated == lotStatus) return false
        lotStatus = evaluated
        return true
    }

    /** 제조일자와 유통기한이 모두 같은지. 한쪽이 없는 것과 있는 것은 다르다 */
    fun hasSameDates(
        manufactureDate: LocalDate?,
        expirationDate: LocalDate?,
    ): Boolean = this.manufactureDate == manufactureDate && this.expirationDate == expirationDate

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Lot) return false
        return lotId != null && lotId == other.lotId
    }

    override fun hashCode(): Int = lotId?.hashCode() ?: System.identityHashCode(this)

    companion object {
        fun create(
            productId: Long,
            lotNumber: String?,
            manufactureDate: LocalDate?,
            expirationDate: LocalDate?,
            today: LocalDate,
            expiringSoonDays: Int,
        ): Lot {
            if (manufactureDate != null && expirationDate != null && expirationDate.isBefore(manufactureDate)) {
                throw InvalidDomainValueException(InventoryErrorCode.INVALID_LOT_DATES)
            }
            return Lot(
                lotId = null,
                productId = productId,
                lotNumber = DomainValidator.requireNotBlank(lotNumber, InventoryErrorCode.INVALID_LOT_NUMBER),
                manufactureDate = manufactureDate,
                expirationDate = expirationDate,
                lotStatus = evaluateStatus(expirationDate, today, expiringSoonDays),
            )
        }

        fun reconstitute(
            lotId: Long,
            productId: Long,
            lotNumber: String,
            manufactureDate: LocalDate?,
            expirationDate: LocalDate?,
            lotStatus: LotStatus,
        ): Lot = Lot(lotId, productId, lotNumber, manufactureDate, expirationDate, lotStatus)

        private fun evaluateStatus(
            expirationDate: LocalDate?,
            today: LocalDate,
            expiringSoonDays: Int,
        ): LotStatus {
            if (expiringSoonDays < 0) throw InvalidDomainValueException(InventoryErrorCode.INVALID_EXPIRING_SOON_DAYS)
            return when {
                expirationDate == null -> LotStatus.NORMAL
                !expirationDate.isAfter(today) -> LotStatus.EXPIRED
                !expirationDate.isAfter(today.plusDays(expiringSoonDays.toLong())) -> LotStatus.EXPIRING_SOON
                else -> LotStatus.NORMAL
            }
        }
    }
}
