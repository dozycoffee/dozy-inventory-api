package com.dozycoffee.ims.global.security

interface CurrentActorProvider {
    suspend fun get(): Actor
}
