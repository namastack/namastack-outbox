package io.namastack.outbox

import io.namastack.outbox.JdbcTestDatabase.MYSQL
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@ResourceLock("jdbc-mysql")
class JdbcOutboxRecordRepositoryMySqlIntegrationTest : AbstractJdbcOutboxRecordRepositoryIntegrationTest() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            MYSQL.registerProperties(registry)
        }
    }
}
