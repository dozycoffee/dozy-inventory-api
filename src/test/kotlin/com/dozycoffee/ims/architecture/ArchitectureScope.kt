package com.dozycoffee.ims.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoBaseDeclaration
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.provider.KoLocationProvider

internal const val ROOT: String = "com.dozycoffee.ims"
internal const val GLOBAL: String = "$ROOT.global"

/** 도메인 패키지(`product` 등) 아래의 계층. 경로는 docs/architecture.md의 패키지 구조와 같다 */
internal enum class Layer(
    val path: String,
) {
    DOMAIN("domain"),
    PORT_IN("application.port.in"),
    PORT_OUT("application.port.out"),
    SERVICE("application.service"),
    ADAPTER_IN("adapter.in"),
    ADAPTER_OUT("adapter.out"),
}

/** 도메인 패키지 안의 위치. `domain`은 `product` 같은 업무 도메인 이름이다 */
internal data class Position(
    val domain: String,
    val layer: Layer,
    val packageName: String,
)

/** Konsist가 패키지 이름에 백틱을 남길 수 있어 제거한다 */
internal fun String.plain(): String = replace("`", "")

/** 패키지나 import 이름에서 도메인 패키지 안의 위치를 구한다. `global`이나 루트 패키지이면 null이다 */
internal fun positionOf(name: String): Position? {
    val plain: String = name.plain()
    if (!plain.startsWith("$ROOT.") || plain.startsWith("$GLOBAL.") || plain == GLOBAL) return null
    val rest: String = plain.removePrefix("$ROOT.")
    val domain: String = rest.substringBefore('.')
    val inside: String = rest.removePrefix(domain).removePrefix(".")
    val layer: Layer = Layer.entries.firstOrNull { inside == it.path || inside.startsWith("${it.path}.") } ?: return null
    return Position(domain, layer, plain)
}

internal fun isGlobal(name: String): Boolean = name.plain().let { it == GLOBAL || it.startsWith("$GLOBAL.") }

internal val productionFiles: List<KoFileDeclaration>
    get() = Konsist.scopeFromProduction().files

internal val productionScope get() = Konsist.scopeFromProduction()

/** 업무 도메인 이름(`global`과 루트 제외) */
internal val domains: Set<String> by lazy {
    productionFiles.mapNotNull { positionOf(it.packageName)?.domain }.toSet()
}

internal val KoFileDeclaration.packageName: String
    get() = packagee?.name.orEmpty()

internal fun KoFileDeclaration.imports(): List<String> = imports.map { it.name.plain() }

internal fun KoFileDeclaration.position(): Position? = positionOf(packageName)

internal fun KoBaseDeclaration.where(): String = (this as? KoLocationProvider)?.location.orEmpty()

/** 규칙 위반을 모아 한 번에 실패시킨다. 테스트 실패 메시지에 위반 목록이 모두 나온다 */
internal fun assertNoViolations(
    rule: String,
    violations: List<String>,
) {
    check(violations.isEmpty()) { "$rule\n${violations.sorted().joinToString("\n") { "  - $it" }}" }
}
