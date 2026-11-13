package io.namastack.outbox

import io.namastack.outbox.JdbcTestDatabase.MARIADB
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Runs the JDBC repository integration checks against MariaDB.
 *
 * @author Aleksander Zamojski
 */
@ResourceLock("jdbc-mariadb")
class JdbcOutboxRecordRepositoryMariaDbIntegrationTest : AbstractJdbcOutboxRecordRepositoryIntegrationTest() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            MARIADB.registerProperties(registry)
        }
    }
}
