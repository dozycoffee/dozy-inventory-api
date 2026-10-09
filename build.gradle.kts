import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.kotlin.plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "8.10.3"
    id("com.epages.restdocs-api-spec") version "0.20.1"
}

group = "com.dozycoffee"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/dozycoffee/dozy-auth")
        credentials {
            username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GPR_USER")
            password = providers.gradleProperty("gpr.token").orNull ?: System.getenv("GPR_TOKEN")
        }
        content { includeGroup("com.dozycoffee.auth") }
    }
}

val dozyAuthVersion: String = "0.2.1"
val restdocsApiSpecVersion: String = "0.20.1"
val konsistVersion: String = "0.17.3"

configurations.testImplementation {
    // restdocs-api-spec이 끌어오는 servlet(Spring MVC, Tomcat) 스택이 있으면 테스트 컨텍스트가 reactive가 아니게 된다
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-web")
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-hateoas")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.flywaydb:flyway-mysql")
    implementation("com.dozycoffee.auth:auth-spring-boot-starter:$dozyAuthVersion")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    runtimeOnly("com.mysql:mysql-connector-j")
    runtimeOnly("io.asyncer:r2dbc-mysql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-r2dbc-test")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testImplementation("com.dozycoffee.auth:auth-test:$dozyAuthVersion")
    testImplementation("org.springframework.restdocs:spring-restdocs-webtestclient")
    testImplementation("com.epages:restdocs-api-spec:$restdocsApiSpecVersion")
    testImplementation("com.epages:restdocs-api-spec-webtestclient:$restdocsApiSpecVersion")
    testImplementation("com.lemonappdev:konsist:$konsistVersion")
    testImplementation("org.testcontainers:testcontainers-mysql")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint("1.8.0").editorConfigOverride(mapOf("ktlint_standard_package-name" to "disabled"))
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint("1.8.0")
    }
}

tasks.test {
    useJUnitPlatform()
    // 통합 테스트가 만든 예약을 스케줄러가 끼어들어 만료 처리하지 않도록 끈다. 스케줄러 테스트는 켜서 직접 실행한다
    systemProperty("inventory.reservation.expiry-scan.enabled", "false")
    // Outbox 발행기도 테스트가 쌓은 이벤트에 끼어들지 않도록 끈다. 발행기 테스트는 켜서 직접 실행한다
    systemProperty("inventory.outbox.publisher.enabled", "false")
    systemProperty("inventory.outbox.cleanup.enabled", "false")
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showCauses = true
        showStackTraces = true
    }
}

// .env의 값을 bootRun 환경변수로 전달한다 (.env는 Git에서 제외된다)
tasks.bootRun {
    val envFile = rootProject.file(".env")
    if (envFile.exists()) {
        envFile
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
            .forEach { line ->
                val index: Int = line.indexOf("=")
                environment(line.substring(0, index).trim(), line.substring(index + 1).trim())
            }
    }
}

openapi3 {
    setServer("http://localhost:8082")
    title = "DOZY COFFEE Inventory API"
    description = "재고 관리(inventory) API"
    version = project.version.toString()
    format = "yaml"
}

// 플러그인이 openapi3 태스크를 평가 이후에 등록하므로 등록되는 시점에 설정한다
tasks.matching { it.name == "openapi3" }.configureEach {
    dependsOn(tasks.test)
}

tasks.build {
    dependsOn("openapi3")
}
