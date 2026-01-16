package io.namastack.outbox.schema

import org.slf4j.LoggerFactory
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MSSQLServerContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
class JdbcSchemaInitializationSqlServerIntegrationTest : AbstractJdbcSchemaInitializationTest() {
    companion object {
        private val log = LoggerFactory.getLogger(JdbcSchemaInitializationMySqlIntegrationTest::class.java)

        @Container
        @JvmStatic
        val sqlserver: MSSQLServerContainer<*> =
            MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-latest")
                .withLogConsumer { log.info(it.utf8StringWithoutLineEnding) }
                .acceptLicense()

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            if (!sqlserver.isRunning) {
                sqlserver.start()
            }

            registry.add("spring.datasource.url") { sqlserver.jdbcUrl }
            registry.add("spring.datasource.username") { sqlserver.username }
            registry.add("spring.datasource.password") { sqlserver.password }
            registry.add("spring.datasource.driver-class-name") { sqlserver.driverClassName }
        }
    }
}
