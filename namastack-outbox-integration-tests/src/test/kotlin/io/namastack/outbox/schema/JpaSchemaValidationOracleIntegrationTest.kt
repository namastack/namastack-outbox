package io.namastack.outbox.schema

import org.slf4j.LoggerFactory
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.OracleContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
class JpaSchemaValidationOracleIntegrationTest : AbstractJpaSchemaValidationIntegrationTest() {
    companion object {
        private val log = LoggerFactory.getLogger(JpaSchemaValidationOracleIntegrationTest::class.java)

        @Container
        @JvmStatic
        val oracle: OracleContainer =
            OracleContainer("gvenzl/oracle-xe:18-slim-faststart")
                .withLogConsumer { log.info(it.utf8StringWithoutLineEnding) }

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            if (!oracle.isRunning) oracle.start()

            // Oracle DDL uses PL/SQL blocks terminated by "/" on its own line.
            // Spring's sql.init splits on ";" by default which breaks PL/SQL, so we
            // execute the script manually via JDBC before the Spring context starts.
            val script =
                JpaSchemaValidationOracleIntegrationTest::class.java.classLoader
                    .getResourceAsStream("schema/oracle/outbox-tables.sql")!!
                    .bufferedReader()
                    .readText()

            oracle.createConnection("").use { conn ->
                script
                    .split(Regex("^/\\s*$", RegexOption.MULTILINE))
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .forEach { block -> conn.createStatement().use { stmt -> stmt.execute(block) } }
            }

            registry.add("spring.datasource.url") { oracle.jdbcUrl }
            registry.add("spring.datasource.username") { oracle.username }
            registry.add("spring.datasource.password") { oracle.password }
            registry.add("spring.datasource.driver-class-name") { oracle.driverClassName }
        }
    }
}
