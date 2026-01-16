package io.namastack.outbox.schema

import org.slf4j.LoggerFactory
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MariaDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
class JdbcSchemaInitializationMariaDbIntegrationTest : AbstractJdbcSchemaInitializationTest() {
    companion object {
        private val log = LoggerFactory.getLogger(JdbcSchemaInitializationMySqlIntegrationTest::class.java)

        @Container
        @JvmStatic
        val mariadb: MariaDBContainer<*> =
            MariaDBContainer("mariadb:12")
                .withLogConsumer { log.info(it.utf8StringWithoutLineEnding) }

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            if (!mariadb.isRunning) {
                mariadb.start()
            }

            registry.add("spring.datasource.url") { mariadb.jdbcUrl }
            registry.add("spring.datasource.username") { mariadb.username }
            registry.add("spring.datasource.password") { mariadb.password }
            registry.add("spring.datasource.driver-class-name") { mariadb.driverClassName }
        }
    }
}
