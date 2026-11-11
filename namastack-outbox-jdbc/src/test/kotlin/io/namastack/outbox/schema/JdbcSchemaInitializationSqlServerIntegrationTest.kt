package io.namastack.outbox.schema

import io.namastack.outbox.JdbcTestDatabase.SQLSERVER
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@ResourceLock("jdbc-sqlserver")
class JdbcSchemaInitializationSqlServerIntegrationTest : AbstractJdbcSchemaInitializationTest() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            SQLSERVER.registerProperties(registry)
        }
    }
}
