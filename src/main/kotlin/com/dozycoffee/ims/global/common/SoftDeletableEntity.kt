package com.dozycoffee.ims.global.common

import java.time.LocalDateTime

abstract class SoftDeletableEntity : BaseEntity() {
    var deletedAt: LocalDateTime? = null
        private set

    var deletedBy: String? = null
        private set

    fun isDeleted(): Boolean = deletedAt != null

    protected fun softDelete(
        actor: String,
        at: LocalDateTime,
    ) {
        this.deletedAt = at
        this.deletedBy = actor
    }
}
