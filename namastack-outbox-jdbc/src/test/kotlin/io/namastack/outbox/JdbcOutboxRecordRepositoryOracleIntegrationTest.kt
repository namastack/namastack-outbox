package io.namastack.outbox

import io.namastack.outbox.JdbcTestDatabase.ORACLE
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@ResourceLock("jdbc-oracle")
class JdbcOutboxRecordRepositoryOracleIntegrationTest : AbstractJdbcOutboxRecordRepositoryIntegrationTest() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            ORACLE.registerProperties(registry)
        }
    }
}
