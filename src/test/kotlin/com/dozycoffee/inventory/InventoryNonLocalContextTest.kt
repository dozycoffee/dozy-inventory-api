package com.dozycoffee.inventory

import com.dozycoffee.inventory.global.security.CurrentActorProvider
import com.dozycoffee.inventory.global.security.SecurityContextActorProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/** `local` 프로필 없이 컨텍스트가 뜨고 토큰 기반 `Actor` 구현이 쓰이는지 확인한다 */
@SpringBootTest
class InventoryNonLocalContextTest {
    @Autowired
    private lateinit var currentActorProvider: CurrentActorProvider

    @Test
    fun `local 프로필이 없으면 토큰 기반 CurrentActorProvider로 컨텍스트가 로드된다`() {
        assertThat(currentActorProvider).isInstanceOf(SecurityContextActorProvider::class.java)
    }
}
