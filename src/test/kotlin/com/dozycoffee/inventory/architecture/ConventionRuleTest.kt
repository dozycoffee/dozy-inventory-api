package com.dozycoffee.inventory.architecture

import com.lemonappdev.konsist.api.declaration.KoFunctionDeclaration
import com.lemonappdev.konsist.api.declaration.KoPropertyDeclaration
import org.junit.jupiter.api.Test

/** Kotlin 코딩 컨벤션 중 기계로 검사할 수 있는 규칙 (docs/conventions.md). production 소스만 검사한다 */
class ConventionRuleTest {
    @Test
    fun `non-null 단언 연산자를 쓰지 않음`() {
        val violations: List<String> =
            productionFiles.filter { NON_NULL_ASSERTION.containsMatchIn(it.codeWithoutCommentsAndStrings()) }.map { it.name }

        assertNoViolations("!!를 쓰지 않는다. ?. / ?: / 스마트 캐스트로 처리한다", violations)
    }

    @Test
    fun `필드 주입을 쓰지 않음`() {
        val violations: List<String> =
            productionFiles
                .filter { file ->
                    file.imports().any { it == "org.springframework.beans.factory.annotation.Autowired" }
                }.map { it.name }

        assertNoViolations("생성자 주입을 쓴다. @Autowired를 쓰지 않는다", violations)
    }

    @Test
    fun `현재 시각은 Clock으로만 얻음`() {
        val violations: List<String> =
            productionFiles.filter { CLOCK_BYPASS.containsMatchIn(it.codeWithoutCommentsAndStrings()) }.map { it.name }

        assertNoViolations("시각은 Clock 빈을 주입받아 쓴다. now()나 System.currentTimeMillis()를 직접 호출하지 않는다", violations)
    }

    @Test
    fun `Transactional은 application service에서만 사용`() {
        val violations: List<String> =
            productionFiles
                .filter { file -> file.imports().any { it in TRANSACTIONAL } }
                .filter { file -> file.position()?.layer != Layer.SERVICE }
                .map { it.name }

        assertNoViolations("트랜잭션 경계는 application/service에 둔다", violations)
    }

    @Test
    fun `Reactor 타입은 global과 adapter에서만 사용`() {
        val violations: List<String> =
            productionFiles
                .filter { file -> file.imports().any { it.startsWith("reactor.") } }
                .filter { file -> !isGlobal(file.packageName) && file.position()?.layer !in setOf(Layer.ADAPTER_IN, Layer.ADAPTER_OUT) }
                .map { it.name }

        assertNoViolations("Reactor(Mono/Flux) 대신 코루틴을 쓴다. 프레임워크 경계(global, adapter)에서만 허용한다", violations)
    }

    @Test
    fun `클래스 프로퍼티는 타입을 명시`() {
        val violations: List<String> =
            productionScope
                .properties(includeNested = true)
                .filterNot { it.hasExplicitType() }
                .map { "${it.containingFile.name}: ${it.name}" }

        assertNoViolations("클래스 프로퍼티의 타입은 추론에 맡기지 않고 명시한다 (함수 본문 안의 지역 변수는 허용)", violations)
    }

    @Test
    fun `함수는 반환 타입을 명시`() {
        val violations: List<String> =
            productionScope
                .functions(includeNested = true, includeLocal = false)
                .filterNot { it.hasExplicitReturnType() }
                .map { "${it.containingFile.name}: ${it.name}" }

        assertNoViolations("함수의 반환 타입은 추론에 맡기지 않고 명시한다. 식 본문(= ...)에도 타입을 쓴다 (블록 본문의 Unit 생략은 허용)", violations)
    }

    private companion object {
        val NON_NULL_ASSERTION: Regex = Regex("""[\w)\]]!!""")
        val CLOCK_BYPASS: Regex =
            Regex("""\b(LocalDateTime|LocalDate|LocalTime|Instant|ZonedDateTime|OffsetDateTime)\.now\(\)|System\.currentTimeMillis\(\)""")
        val TRANSACTIONAL: Set<String> =
            setOf("org.springframework.transaction.annotation.Transactional", "jakarta.transaction.Transactional")
    }
}

/** 주석과 문자열 리터럴을 지운 코드. 규칙의 문구가 주석이나 메시지에 있어도 오탐하지 않도록 한다 */
private fun com.lemonappdev.konsist.api.declaration.KoFileDeclaration.codeWithoutCommentsAndStrings(): String =
    text
        .replace(Regex("""/\*[\s\S]*?\*/"""), "")
        .replace(Regex("""//.*"""), "")
        .replace(Regex(""""(?:\\.|[^"\\])*""""), "\"\"")

/** `val name: Type`처럼 이름 뒤에 `:`로 타입을 적었는지. Konsist 0.17은 추론한 타입과 명시한 타입을 구분하지 않아 소스 텍스트로 판단한다 */
private fun KoPropertyDeclaration.hasExplicitType(): Boolean {
    val declaration: String = text.substringAfter(Regex("""\b(val|var)\b""").find(text)?.value ?: return true)
    val firstSeparator: Int = declaration.indexOfFirst { it == ':' || it == '=' }
    return firstSeparator >= 0 && declaration[firstSeparator] == ':'
}

/**
 * 반환 타입을 추론에 맡기지 않았는지. 매개변수 목록의 닫는 괄호 뒤에 `:`가 오면 명시한 것이고, 식 본문(`= ...`)은 추론이라 위반이다.
 * 블록 본문(`{`)이나 본문 없는 선언은 Unit이며 ktlint no-unit-return이 `: Unit`을 지우므로 생략을 허용한다.
 */
private fun KoFunctionDeclaration.hasExplicitReturnType(): Boolean {
    val start: MatchResult = Regex("""\bfun\b[^(=]*?\b${Regex.escape(name)}\s*\(""").find(text) ?: return true
    var depth = 1
    var index: Int = start.range.last + 1
    var inString = false
    while (index < text.length && depth > 0) {
        val char: Char = text[index]
        when {
            char == '"' && text.getOrNull(index - 1) != '\\' -> inString = !inString
            !inString && char == '(' -> depth++
            !inString && char == ')' -> depth--
        }
        index++
    }
    return !text.substring(index).trimStart().startsWith("=")
}
