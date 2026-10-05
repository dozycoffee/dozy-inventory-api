package com.dozycoffee.ims.support

import org.springframework.boot.test.util.TestPropertyValues
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.testcontainers.mysql.MySQLContainer

class MySqlTestContainerInitializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
    override fun initialize(applicationContext: ConfigurableApplicationContext) {
        val mysql: MySQLContainer = container
        TestPropertyValues
            .of(
                "spring.r2dbc.url=r2dbc:mysql://${mysql.host}:${mysql.firstMappedPort}/$DATABASE_NAME?useSSL=false&serverTimezone=Asia/Seoul",
                "spring.r2dbc.username=root",
                "spring.r2dbc.password=${mysql.password}",
                "spring.flyway.url=${mysql.jdbcUrl}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul",
                "spring.flyway.user=root",
                "spring.flyway.password=${mysql.password}",
            ).applyTo(applicationContext.environment)
    }

    private companion object {
        const val DATABASE_NAME: String = "dozy_inventory"

        // 테스트 JVM당 1회만 기동하고 JVM 종료 시 Ryuk가 정리한다
        val container: MySQLContainer by lazy {
            MySQLContainer("mysql:8.0")
                .withDatabaseName(DATABASE_NAME)
                .withEnv("TZ", "Asia/Seoul")
                .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci")
                .also { it.start() }
        }
    }
}
