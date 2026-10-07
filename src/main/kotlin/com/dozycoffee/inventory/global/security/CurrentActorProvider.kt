package com.dozycoffee.inventory.global.security

interface CurrentActorProvider {
    suspend fun get(): Actor
}
