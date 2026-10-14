package com.dozycoffee.inventory.global.security

import com.dozycoffee.auth.starter.DozyAuthProperties
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.config.web.server.invoke
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.ServerAuthenticationEntryPoint
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.reactive.CorsConfigurationSource
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource
import reactor.core.publisher.Mono

/**
 * 토큰 검증 부품(디코더, 권한 변환기, 401/403 핸들러)은 dozy-auth 스타터 빈을 그대로 쓴다.
 * 체인은 직접 정의해 `local` 프로필에서만 인증을 우회한다.
 *
 * CORS도 이 체인 안에서 처리한다. 인증 필터가 핸들러 매핑보다 먼저 실행되므로, 토큰 없는 preflight(OPTIONS)와
 * 401·403 응답에도 CORS 헤더가 붙어야 브라우저(어드민 콘솔)가 실제 상태 코드를 읽을 수 있다.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig(
    @param:Value("\${inventory.cors.allowed-origins}") private val allowedOrigins: List<String>,
) {
    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration =
            CorsConfiguration().apply {
                allowedOrigins = this@SecurityConfig.allowedOrigins
                allowedMethods = listOf("GET", "POST", "PATCH", "OPTIONS")
                allowedHeaders = listOf("Authorization", "Content-Type", "Idempotency-Key")
            }
        return UrlBasedCorsConfigurationSource().apply { registerCorsConfiguration("/**", configuration) }
    }

    @Bean
    @Profile("!local")
    fun securityWebFilterChain(
        http: ServerHttpSecurity,
        properties: DozyAuthProperties,
        jwtDecoder: ReactiveJwtDecoder,
        dozyJwtAuthenticationConverter: Converter<Jwt, Mono<AbstractAuthenticationToken>>,
        entryPoint: ServerAuthenticationEntryPoint,
        accessDeniedHandler: ServerAccessDeniedHandler,
    ): SecurityWebFilterChain =
        http {
            cors { configurationSource = corsConfigurationSource() }
            authorizeExchange {
                properties.publicPaths.forEach { authorize(it, permitAll) }
                authorize(anyExchange, authenticated)
            }
            oauth2ResourceServer {
                jwt {
                    this.jwtDecoder = jwtDecoder
                    jwtAuthenticationConverter = dozyJwtAuthenticationConverter
                }
                authenticationEntryPoint = entryPoint
            }
            exceptionHandling {
                authenticationEntryPoint = entryPoint
                this.accessDeniedHandler = accessDeniedHandler
            }
            securityContextRepository = NoOpServerSecurityContextRepository.getInstance()
            csrf { disable() }
            httpBasic { disable() }
            formLogin { disable() }
            logout { disable() }
        }

    /** 로컬 개발 전용. 토큰 없이 모든 role을 가진 개발 사용자로 호출할 수 있게 한다 */
    @Bean
    @Profile("local")
    fun localSecurityWebFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain =
        http {
            cors { configurationSource = corsConfigurationSource() }
            authorizeExchange { authorize(anyExchange, permitAll) }
            anonymous {
                principal = LocalActorProvider.LOCAL_PRINCIPAL_ID
                authorities = LocalActorProvider.LOCAL_USER.roles.map { SimpleGrantedAuthority("ROLE_$it") }
            }
            securityContextRepository = NoOpServerSecurityContextRepository.getInstance()
            csrf { disable() }
            httpBasic { disable() }
            formLogin { disable() }
            logout { disable() }
        }
}
