package com.dozycoffee.inventory.global.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.MutablePropertySources
import org.springframework.core.env.PropertySource
import org.springframework.core.env.PropertySourcesPropertyResolver
import org.springframework.core.io.ClassPathResource

/**
 * 운영 프로필은 로컬 개발용 기본값을 없애 필수 환경변수가 빠지면 설정을 읽는 단계에서 실패해야 한다(ADR-0036).
 * 프로세스 환경변수의 영향을 받지 않도록 설정 파일만 읽어 확인한다.
 */
class ProdProfileConfigurationTest {
    private val required: List<String> =
        listOf("DB_HOST", "DB_PORT", "DB_NAME", "DB_USERNAME", "DB_PASSWORD", "KAFKA_BOOTSTRAP_SERVERS", "AUTH_ISSUER_BASE_URI")

    private val values: Map<String, Any> =
        mapOf(
            "DB_HOST" to "db.internal",
            "DB_PORT" to "3306",
            "DB_NAME" to "dozy_inventory",
            "DB_USERNAME" to "inventory",
            "DB_PASSWORD" to "secret",
            "KAFKA_BOOTSTRAP_SERVERS" to "kafka.internal:9092",
            "AUTH_ISSUER_BASE_URI" to "https://auth.internal",
        )

    /** 운영 프로필 파일이 기본 파일보다 우선하도록 앞에 둔 해석기. [env]는 환경변수 역할이다 */
    private fun resolver(
        env: Map<String, Any>,
        withProd: Boolean = true,
    ): PropertySourcesPropertyResolver {
        val loader = YamlPropertySourceLoader()
        val sources = MutablePropertySources()
        sources.addLast(MapPropertySource("env", env))
        if (withProd) loader.load("prod", ClassPathResource("application-prod.yaml")).forEach { sources.addLast(it) }
        loader.load("base", ClassPathResource("application.yaml")).forEach { sources.addLast(it as PropertySource<*>) }
        return PropertySourcesPropertyResolver(sources)
    }

    @Nested
    inner class `운영 프로필` {
        @Test
        fun `필수 환경변수를 모두 주면 연결 정보가 환경변수 값으로 만들어진다`() {
            val resolver = resolver(values)

            assertThat(resolver.getProperty("spring.r2dbc.url")).startsWith("r2dbc:mysql://db.internal:3306/dozy_inventory?")
            assertThat(resolver.getProperty("spring.flyway.url")).startsWith("jdbc:mysql://db.internal:3306/dozy_inventory?")
            assertThat(resolver.getProperty("spring.r2dbc.username")).isEqualTo("inventory")
            assertThat(resolver.getProperty("spring.flyway.user")).isEqualTo("inventory")
            assertThat(resolver.getProperty("spring.kafka.bootstrap-servers")).isEqualTo("kafka.internal:9092")
            assertThat(resolver.getProperty("dozy.auth.issuer-base-uri")).isEqualTo("https://auth.internal")
        }

        @Test
        fun `필수 환경변수가 하나라도 빠지면 해당 설정을 읽을 때 실패한다`() {
            val properties: Map<String, String> =
                mapOf(
                    "DB_HOST" to "spring.r2dbc.url",
                    "DB_PORT" to "spring.r2dbc.url",
                    "DB_NAME" to "spring.flyway.url",
                    "DB_USERNAME" to "spring.r2dbc.username",
                    "DB_PASSWORD" to "spring.r2dbc.password",
                    "KAFKA_BOOTSTRAP_SERVERS" to "spring.kafka.bootstrap-servers",
                    "AUTH_ISSUER_BASE_URI" to "dozy.auth.issuer-base-uri",
                )
            assertThat(properties.keys).containsExactlyInAnyOrderElementsOf(required)

            properties.forEach { (name, property) ->
                val resolver = resolver(values - name)
                assertThatThrownBy { resolver.getProperty(property) }
                    .describedAs("%s가 없을 때 %s", name, property)
                    .isInstanceOf(IllegalArgumentException::class.java)
                    .hasMessageContaining(name)
            }
        }
    }

    @Nested
    inner class `기본 설정` {
        @Test
        fun `운영 프로필이 없으면 로컬 개발용 기본값으로 읽는다`() {
            val resolver = resolver(mapOf("DB_PASSWORD" to "secret"), withProd = false)

            assertThat(resolver.getProperty("spring.r2dbc.url")).startsWith("r2dbc:mysql://localhost:3307/dozy_inventory?")
            assertThat(resolver.getProperty("spring.kafka.bootstrap-servers")).isEqualTo("localhost:9092")
        }

        @Test
        fun `비밀번호는 기본값이 없어 운영 프로필 여부와 상관없이 필수이다`() {
            assertThatThrownBy { resolver(emptyMap(), withProd = false).getProperty("spring.r2dbc.password") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("DB_PASSWORD")
        }
    }
}
