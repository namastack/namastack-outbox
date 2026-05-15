package io.namastack.outbox.schema

import org.junit.jupiter.api.Disabled
import org.slf4j.LoggerFactory
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mssqlserver.MSSQLServerContainer

@Testcontainers
@Disabled(
    "SQL Server validation currently fails on Instant timestamp columns because Hibernate 7 maps them to " +
        "DATETIMEOFFSET(7), while the shipped SQL Server DDL intentionally remains on DATETIME2(6) for " +
        "backwards-compatible JDBC behavior. Track and fix this separately from the payload/context schema fix.",
)
class JpaSchemaValidationSqlServerIntegrationTest : AbstractJpaSchemaValidationIntegrationTest() {
    companion object {
        private val log = LoggerFactory.getLogger(JpaSchemaValidationSqlServerIntegrationTest::class.java)

        @Container
        @JvmStatic
        val sqlserver: MSSQLServerContainer =
            MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-latest")
                .withLogConsumer { log.info(it.utf8StringWithoutLineEnding) }
                .acceptLicense()

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            if (!sqlserver.isRunning) sqlserver.start()

            registry.add("spring.datasource.url") { sqlserver.jdbcUrl }
            registry.add("spring.datasource.username") { sqlserver.username }
            registry.add("spring.datasource.password") { sqlserver.password }
            registry.add("spring.datasource.driver-class-name") { sqlserver.driverClassName }
            registry.add("spring.sql.init.schema-locations") { "classpath:schema/sqlserver/outbox-tables.sql" }
            registry.add("spring.sql.init.mode") { "always" }
        }
    }
}
