package io.namastack.outbox.schema

import org.slf4j.LoggerFactory
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.OracleContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
class JdbcSchemaInitializationOracleIntegrationTest : AbstractJdbcSchemaInitializationTest() {
    companion object {
        private val log = LoggerFactory.getLogger(JdbcSchemaInitializationMySqlIntegrationTest::class.java)

        @Container
        @JvmStatic
        val oracle: OracleContainer =
            OracleContainer("gvenzl/oracle-xe:18-slim-faststart")
                .withLogConsumer { log.info(it.utf8StringWithoutLineEnding) }

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            if (!oracle.isRunning) {
                oracle.start()
            }

            registry.add("spring.datasource.url") { oracle.jdbcUrl }
            registry.add("spring.datasource.username") { oracle.username }
            registry.add("spring.datasource.password") { oracle.password }
            registry.add("spring.datasource.driver-class-name") { oracle.driverClassName }
        }
    }
}
