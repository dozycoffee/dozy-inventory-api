package com.dozycoffee.inventory.architecture

import com.lemonappdev.konsist.api.declaration.KoClassDeclaration
import com.lemonappdev.konsist.api.declaration.KoInterfaceDeclaration
import org.junit.jupiter.api.Test

/** 이름 접미사와 패키지 위치 규칙 (docs/architecture.md "이름 규칙", ADR-0010). 도메인 패키지 안의 선언만 검사한다 */
class NamingRuleTest {
    private companion object {
        /** 영속성 엔티티의 감사 컬럼 기반 클래스. 생성만 → +수정 → +삭제 순으로 한 줄기로 상속한다 */
        val AUDIT_BASES: Array<String> = arrayOf("CreatedAuditEntity", "BaseEntity", "SoftDeletableEntity")

        /** 감사 기반 클래스에 맞지 않는 테이블의 엔티티. `outbox_event`는 `created_by`가 없어 `@CreatedDate`만 직접 둔다(architecture.md) */
        val AUDIT_BASE_EXEMPT: Set<String> = setOf("OutboxEventEntity")
    }

    /** 도메인 패키지 안의 클래스·인터페이스·enum·object. (이름, 도메인 패키지 이름, 종류) */
    private data class Declaration(
        val name: String,
        val position: Position,
        val kind: Kind,
        val declaration: Any,
    )

    private enum class Kind { CLASS, INTERFACE, ENUM, OBJECT }

    private val declarations: List<Declaration> by lazy {
        val scope = productionScope
        val classes: List<Declaration> =
            scope.classes().mapNotNull { c ->
                val position: Position = positionOf(c.packagee?.name.orEmpty()) ?: return@mapNotNull null
                Declaration(c.name, position, if (c.hasEnumModifier) Kind.ENUM else Kind.CLASS, c)
            }
        val interfaces: List<Declaration> =
            scope.interfaces().mapNotNull { i ->
                val position: Position = positionOf(i.packagee?.name.orEmpty()) ?: return@mapNotNull null
                Declaration(i.name, position, Kind.INTERFACE, i)
            }
        val objects: List<Declaration> =
            scope.objects().mapNotNull { o ->
                val position: Position = positionOf(o.packagee?.name.orEmpty()) ?: return@mapNotNull null
                Declaration(o.name, position, Kind.OBJECT, o)
            }
        classes + interfaces + objects
    }

    /** 접미사가 `suffix`인 선언은 `kinds` 중 하나이고 도메인 패키지 안의 `path` 패키지에 있어야 한다. `exclude`는 같은 접미사를 쓰지만 다른 규칙을 따르는 이름이다 */
    private data class Rule(
        val suffix: String,
        val kinds: Set<Kind>,
        val layer: Layer,
        val subPackage: String = "",
        val exclude: String? = null,
    ) {
        val expected: String get() = (layer.path + subPackage.let { if (it.isEmpty()) "" else ".$it" })
    }

    private val rules: List<Rule> =
        listOf(
            Rule("UseCase", setOf(Kind.INTERFACE), Layer.PORT_IN),
            Rule("Command", setOf(Kind.CLASS), Layer.PORT_IN, "command"),
            Rule("Query", setOf(Kind.CLASS), Layer.PORT_IN, "command"),
            Rule("Result", setOf(Kind.CLASS), Layer.PORT_IN, "result"),
            Rule("Repository", setOf(Kind.INTERFACE), Layer.PORT_OUT, exclude = "R2dbcRepository"),
            Rule("R2dbcRepository", setOf(Kind.INTERFACE), Layer.ADAPTER_OUT, "persistence"),
            Rule("PersistenceAdapter", setOf(Kind.CLASS), Layer.ADAPTER_OUT, "persistence"),
            Rule("Entity", setOf(Kind.CLASS), Layer.ADAPTER_OUT, "persistence"),
            Rule("Service", setOf(Kind.CLASS), Layer.SERVICE),
            Rule("Controller", setOf(Kind.CLASS), Layer.ADAPTER_IN, "web"),
            Rule("Request", setOf(Kind.CLASS), Layer.ADAPTER_IN, "web.request"),
            Rule("Response", setOf(Kind.CLASS), Layer.ADAPTER_IN, "web.response"),
            Rule("ErrorCode", setOf(Kind.ENUM), Layer.DOMAIN, "exception"),
            Rule("Exception", setOf(Kind.CLASS), Layer.DOMAIN, "exception"),
        )

    @Test
    fun `접미사가 붙은 선언은 정해진 종류와 패키지에 있음`() {
        val violations: List<String> =
            declarations.mapNotNull { d ->
                val rule: Rule =
                    rules.firstOrNull { d.name.endsWith(it.suffix) && (it.exclude == null || !d.name.endsWith(it.exclude)) }
                        ?: return@mapNotNull null
                val expectedPackage: String = "${d.position.domain}.${rule.expected}"
                val actual: String = d.position.packageName.removePrefix("$ROOT.")
                when {
                    d.kind !in rule.kinds -> "${d.name}은(는) ${rule.suffix} 접미사에 맞는 종류(${rule.kinds})가 아니다: ${d.kind}"
                    actual != expectedPackage -> "${d.name}: $actual → $expectedPackage"
                    else -> null
                }
            }

        assertNoViolations("이름 접미사와 위치가 architecture.md와 맞지 않는다", violations)
    }

    @Test
    fun `service와 port in 패키지의 선언은 접미사 규칙을 따름`() {
        val violations: List<String> =
            declarations.mapNotNull { d ->
                when {
                    d.position.layer == Layer.SERVICE && !d.name.endsWith("Service") -> {
                        "${d.name}은(는) application/service이므로 Service로 끝나야 한다"
                    }

                    d.position.layer == Layer.PORT_IN && d.position.packageName.endsWith(".port.in") && !d.name.endsWith("UseCase") -> {
                        "${d.name}은(는) port/in 바로 아래이므로 UseCase로 끝나야 한다"
                    }

                    else -> {
                        null
                    }
                }
            }

        assertNoViolations("패키지에 맞는 접미사를 써야 한다", violations)
    }

    @Test
    fun `PersistenceAdapter는 출력 포트를 구현하고 Entity는 감사 기반 클래스를 상속하며 Controller는 RestController`() {
        val violations: List<String> =
            declarations
                .filter { it.kind == Kind.CLASS }
                .mapNotNull { d ->
                    val c: KoClassDeclaration = d.declaration as KoClassDeclaration
                    when {
                        d.name.endsWith(
                            "PersistenceAdapter",
                        ) && c.containingFile.imports().none { positionOf(it)?.layer == Layer.PORT_OUT } -> {
                            "${d.name}은(는) application/port/out의 포트를 구현해야 한다"
                        }

                        d.name.endsWith("Entity") && d.name !in AUDIT_BASE_EXEMPT && !c.hasParentWithName(AUDIT_BASES.toList()) -> {
                            "${d.name}은(는) 감사 기반 클래스(CreatedAuditEntity, BaseEntity, SoftDeletableEntity)를 상속해야 한다"
                        }

                        d.name.endsWith("Controller") && !c.hasAnnotationWithName("RestController") -> {
                            "${d.name}에는 @RestController가 필요하다"
                        }

                        else -> {
                            null
                        }
                    }
                }

        assertNoViolations("구성 요소의 형태 규칙 위반", violations)
    }

    @Test
    fun `도메인 모델은 data class가 아니고 BaseEntity를 상속하지 않으며 감사 필드를 바꾸지 않음`() {
        val models: List<KoClassDeclaration> =
            declarations
                .filter {
                    it.kind == Kind.CLASS && it.position.layer == Layer.DOMAIN &&
                        it.position.packageName.endsWith(".domain.model")
                }.map { it.declaration as KoClassDeclaration }
        val auditNames: Regex = Regex("(created|updated|deleted)(At|By)")

        val violations: List<String> =
            models.flatMap { c ->
                buildList {
                    if (c.hasDataModifier) add("${c.name}은(는) data class이다. copy()가 create()의 검증을 우회한다")
                    if (c.hasParentWithName(AUDIT_BASES.toList())) add("${c.name}은(는) 감사 기반 클래스를 상속한다. 도메인 모델은 감사 필드를 모른다")
                    c
                        .properties()
                        .filter {
                            auditNames.matches(
                                it.name,
                            ) && it.isVar
                        }.forEach { add("${c.name}.${it.name}: 도메인 모델에 변경 가능한 감사 필드가 있다") }
                }
            }

        assertNoViolations("도메인 모델 규칙 (conventions.md, ADR-0010)", violations)
    }

    @Test
    fun `Konsist가 도메인 패키지의 선언을 찾음`() {
        val interfaces: List<KoInterfaceDeclaration> = productionScope.interfaces()

        check(declarations.isNotEmpty() && interfaces.isNotEmpty()) { "도메인 패키지의 선언을 찾지 못했다. Konsist 범위나 패키지 경로를 확인한다" }
    }
}
