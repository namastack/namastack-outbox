package io.namastack.outbox

import io.namastack.outbox.JdbcTestDatabase.MYSQL
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Runs the JDBC repository integration checks against MySQL.
 *
 * @author Aleksander Zamojski
 */
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
