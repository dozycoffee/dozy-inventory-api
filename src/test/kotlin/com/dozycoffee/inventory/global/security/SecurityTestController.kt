package com.dozycoffee.inventory.global.security

import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/test/security")
class SecurityTestController(
    private val currentActorProvider: CurrentActorProvider,
) {
    @PreAuthorize(InventoryAuthorize.ANY)
    @GetMapping("/any")
    suspend fun any(): String = "ok"

    @PreAuthorize(InventoryAuthorize.ADMIN)
    @GetMapping("/admin")
    suspend fun admin(): String = "ok"

    @PreAuthorize(InventoryAuthorize.ANY)
    @GetMapping("/actor")
    suspend fun actor(): Map<String, Any> {
        val actor: UserActor = currentActorProvider.get() as UserActor
        return mapOf("auditName" to actor.auditName, "roles" to actor.roles.sorted())
    }
}
