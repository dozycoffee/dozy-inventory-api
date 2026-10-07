package com.dozycoffee.inventory.global.config

import com.dozycoffee.inventory.global.security.CurrentActorProvider
import kotlinx.coroutines.reactor.mono
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.auditing.DateTimeProvider
import org.springframework.data.domain.ReactiveAuditorAware
import org.springframework.data.r2dbc.config.EnableR2dbcAuditing
import java.time.Clock
import java.time.LocalDateTime
import java.util.Optional

@Configuration
@EnableR2dbcAuditing(auditorAwareRef = "auditorAware", dateTimeProviderRef = "auditingDateTimeProvider")
class R2dbcConfig(
    private val currentActorProvider: CurrentActorProvider,
    private val clock: Clock,
) {
    @Bean
    fun auditorAware(): ReactiveAuditorAware<String> = ReactiveAuditorAware { mono { currentActorProvider.get().auditName } }

    @Bean
    fun auditingDateTimeProvider(): DateTimeProvider = DateTimeProvider { Optional.of(LocalDateTime.now(clock)) }
}
