package io.namastack.outbox.scheduling

import io.namastack.outbox.JdbcTestDatabase.MARIADB
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@ResourceLock("jdbc-mariadb")
class JdbcConcurrentSchedulingMariaDbIntegrationTest : AbstractJdbcConcurrentSchedulingTest() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            MARIADB.registerProperties(registry)
        }
    }
}
