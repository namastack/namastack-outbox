package io.namastack.outbox.scheduling

import io.namastack.outbox.JdbcTestDatabase.POSTGRES
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@ResourceLock("jdbc-postgres")
class JdbcConcurrentSchedulingPostgresIntegrationTest : AbstractJdbcConcurrentSchedulingTest() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            POSTGRES.registerProperties(registry)
        }
    }
}
