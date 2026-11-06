package io.namastack.outbox

import io.namastack.outbox.JdbcTestDatabase.ORACLE
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Runs the JDBC repository integration checks against Oracle.
 *
 * @author Aleksander Zamojski
 */
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
