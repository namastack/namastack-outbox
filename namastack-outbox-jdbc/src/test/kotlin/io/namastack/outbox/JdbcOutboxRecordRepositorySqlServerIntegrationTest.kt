package io.namastack.outbox

import io.namastack.outbox.JdbcTestDatabase.SQLSERVER
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@ResourceLock("jdbc-sqlserver")
class JdbcOutboxRecordRepositorySqlServerIntegrationTest : AbstractJdbcOutboxRecordRepositoryIntegrationTest() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            SQLSERVER.registerProperties(registry)
        }
    }
}
