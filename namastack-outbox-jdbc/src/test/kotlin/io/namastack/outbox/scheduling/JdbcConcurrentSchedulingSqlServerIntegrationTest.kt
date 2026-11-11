package io.namastack.outbox.scheduling

import io.namastack.outbox.JdbcTestDatabase.SQLSERVER
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@ResourceLock("jdbc-sqlserver")
class JdbcConcurrentSchedulingSqlServerIntegrationTest : AbstractJdbcConcurrentSchedulingTest() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            SQLSERVER.registerProperties(registry)
        }
    }
}
