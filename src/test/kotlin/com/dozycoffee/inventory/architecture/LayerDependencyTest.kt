package com.dozycoffee.inventory.architecture

import org.junit.jupiter.api.Test

/**
 * 레이어 의존 방향과 도메인 간 호출 규칙 (docs/architecture.md "레이어 규칙", "도메인 간 호출", ADR-0010).
 * 테스트 코드는 대상이 아니고 production 소스만 검사한다.
 */
class LayerDependencyTest {
    /** 계층 → 같은 도메인 안에서 import해도 되는 계층 */
    private val allowed: Map<Layer, Set<Layer>> =
        mapOf(
            Layer.DOMAIN to setOf(Layer.DOMAIN),
            Layer.PORT_IN to setOf(Layer.PORT_IN, Layer.DOMAIN),
            Layer.PORT_OUT to setOf(Layer.PORT_OUT, Layer.PORT_IN, Layer.DOMAIN),
            Layer.SERVICE to setOf(Layer.SERVICE, Layer.PORT_IN, Layer.PORT_OUT, Layer.DOMAIN),
            Layer.ADAPTER_IN to setOf(Layer.ADAPTER_IN, Layer.PORT_IN, Layer.DOMAIN),
            Layer.ADAPTER_OUT to setOf(Layer.ADAPTER_OUT, Layer.PORT_OUT, Layer.DOMAIN),
        )

    @Test
    fun `도메인 패키지는 정해진 방향의 같은 도메인 계층만 import`() {
        val violations: List<String> =
            productionFiles.flatMap { file ->
                val own: Position = file.position() ?: return@flatMap emptyList()
                file.imports().mapNotNull { import ->
                    val target: Position = positionOf(import) ?: return@mapNotNull null
                    if (target.domain != own.domain || target.layer in allowed.getValue(own.layer)) {
                        null
                    } else {
                        "${own.layer.path} → ${target.layer.path}: ${file.name} imports $import"
                    }
                }
            }

        assertNoViolations("계층 의존 방향 위반 (architecture.md 레이어 규칙)", violations)
    }

    @Test
    fun `다른 도메인은 그 도메인의 application port in만 import`() {
        val violations: List<String> =
            productionFiles.flatMap { file ->
                val own: Position = file.position() ?: return@flatMap emptyList()
                file.imports().mapNotNull { import ->
                    val target: Position = positionOf(import) ?: return@mapNotNull null
                    if (target.domain == own.domain || target.layer == Layer.PORT_IN) {
                        null
                    } else {
                        "${own.domain} → ${target.domain}: ${file.name} imports $import"
                    }
                }
            }

        assertNoViolations("다른 도메인은 UseCase(application/port/in)로만 호출한다 (ADR-0010)", violations)
    }

    @Test
    fun `global은 도메인 패키지를 import하지 않음`() {
        val violations: List<String> =
            productionFiles
                .filter { isGlobal(it.packageName) }
                .flatMap { file ->
                    file.imports().filter { positionOf(it) != null }.map { "${file.name} imports $it" }
                }

        assertNoViolations("global은 업무 도메인에 의존하지 않는다", violations)
    }

    @Test
    fun `domain은 global error·domain 외의 global과 기술 패키지를 import하지 않음`() {
        val violations: List<String> =
            filesIn(Layer.DOMAIN).flatMap { file ->
                file
                    .imports()
                    .filter { import ->
                        import.startsWithAny(*DOMAIN_FORBIDDEN) ||
                            (isGlobal(import) && !import.startsWith("$GLOBAL.error.") && !import.startsWith("$GLOBAL.domain."))
                    }.map { "${file.name} imports $it" }
            }

        assertNoViolations(
            "domain은 순수 Kotlin이다. Spring·R2DBC·Reactor·jakarta와 global.error·global.domain 외 global을 import하지 않는다",
            violations,
        )
    }

    @Test
    fun `application은 웹과 영속성 기술을 import하지 않음`() {
        val violations: List<String> =
            listOf(Layer.PORT_IN, Layer.PORT_OUT, Layer.SERVICE).flatMap { layer ->
                filesIn(layer).flatMap { file ->
                    file
                        .imports()
                        .filter { import ->
                            import.startsWithAny(*PERSISTENCE, *WEB, "reactor.") ||
                                import.startsWithAny("$GLOBAL.persistence.", "$GLOBAL.config.", "$GLOBAL.common.")
                        }.map { "${file.name} imports $it" }
                }
            }

        assertNoViolations("application은 웹·영속성 기술을 모른다. 포트로 추상화한다", violations)
    }

    @Test
    fun `adapter in은 영속성 기술을 import하지 않음`() {
        val violations: List<String> =
            filesIn(Layer.ADAPTER_IN).flatMap { file ->
                file
                    .imports()
                    .filter { it.startsWithAny(*PERSISTENCE) || it.startsWith("$GLOBAL.persistence.") }
                    .map { "${file.name} imports $it" }
            }

        assertNoViolations("adapter/in은 영속성 기술을 쓰지 않는다. UseCase를 호출한다", violations)
    }

    private fun filesIn(layer: Layer) = productionFiles.filter { file -> file.position()?.layer == layer }

    private fun String.startsWithAny(vararg prefixes: String): Boolean = prefixes.any { startsWith(it) }

    private companion object {
        val DOMAIN_FORBIDDEN: Array<String> =
            arrayOf("org.springframework.", "io.r2dbc.", "reactor.", "kotlinx.coroutines.reactor.", "jakarta.")
        val PERSISTENCE: Array<String> = arrayOf("io.r2dbc.", "org.springframework.data.", "org.springframework.r2dbc.")
        val WEB: Array<String> = arrayOf("org.springframework.web.", "org.springframework.http.", "jakarta.servlet.")
    }
}
