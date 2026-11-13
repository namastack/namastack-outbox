package io.namastack.outbox

import io.namastack.outbox.JdbcTestDatabase.POSTGRES
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Runs the JDBC repository integration checks against PostgreSQL.
 *
 * @author Aleksander Zamojski
 */
@ResourceLock("jdbc-postgres")
class JdbcOutboxRecordRepositoryPostgresIntegrationTest : AbstractJdbcOutboxRecordRepositoryIntegrationTest() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            POSTGRES.registerProperties(registry)
        }
    }
}
