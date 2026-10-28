package io.namastack.outbox.scheduling

import org.slf4j.LoggerFactory
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mysql.MySQLContainer

@Testcontainers
class JdbcConcurrentSchedulingMySqlIntegrationTest : AbstractJdbcConcurrentSchedulingTest() {
    companion object {
        private val log = LoggerFactory.getLogger(JdbcConcurrentSchedulingMySqlIntegrationTest::class.java)

        @Container
        @JvmStatic
        val mysql: MySQLContainer =
            MySQLContainer("mysql:9.2")
                .withLogConsumer { log.info(it.utf8StringWithoutLineEnding) }

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            if (!mysql.isRunning) {
                mysql.start()
            }

            registry.add("spring.datasource.url") { mysql.jdbcUrl }
            registry.add("spring.datasource.username") { mysql.username }
            registry.add("spring.datasource.password") { mysql.password }
            registry.add("spring.datasource.driver-class-name") { mysql.driverClassName }
        }
    }
}
