package io.namastack.outbox.schema

import io.namastack.outbox.JdbcTestDatabase.MARIADB
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@ResourceLock("jdbc-mariadb")
class JdbcSchemaInitializationMariaDbIntegrationTest : AbstractJdbcSchemaInitializationTest() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            MARIADB.registerProperties(registry)
        }
    }
}
