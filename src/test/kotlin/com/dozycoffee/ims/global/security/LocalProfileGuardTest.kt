package com.dozycoffee.ims.global.security

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

class LocalProfileGuardTest {
    @Test
    fun `local 프로필만 켜져 있으면 기동할 수 있다`() {
        val environment: MockEnvironment = MockEnvironment().withProperty("spring.profiles.active", "local")
        environment.setActiveProfiles("local")

        assertThatCode { LocalProfileGuard(environment) }.doesNotThrowAnyException()
    }

    @Test
    fun `local 프로필이 prod 프로필과 함께 켜지면 기동을 막는다`() {
        val environment: MockEnvironment = MockEnvironment()
        environment.setActiveProfiles("local", "prod")

        assertThatThrownBy { LocalProfileGuard(environment) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("prod")
    }
}
