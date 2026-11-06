package io.namastack.outbox

import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer

/**
 * Runs the JPA repository integration checks against MySQL.
 *
 * @author Aleksander Zamojski
 */
@Testcontainers
class JpaOutboxRecordRepositoryMySqlIntegrationTest : AbstractJpaOutboxRecordRepositoryIntegrationTest() {
    companion object {
        @Container
        @JvmStatic
        val mysql = MySQLContainer("mysql:9.2")

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { mysql.jdbcUrl }
            registry.add("spring.datasource.username") { mysql.username }
            registry.add("spring.datasource.password") { mysql.password }
            registry.add("spring.datasource.driver-class-name") { mysql.driverClassName }
        }
    }
}
