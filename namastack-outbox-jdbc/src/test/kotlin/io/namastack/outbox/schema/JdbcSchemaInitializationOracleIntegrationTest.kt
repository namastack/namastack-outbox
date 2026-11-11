package io.namastack.outbox.schema

import io.namastack.outbox.JdbcTestDatabase.ORACLE
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@ResourceLock("jdbc-oracle")
class JdbcSchemaInitializationOracleIntegrationTest : AbstractJdbcSchemaInitializationTest() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            ORACLE.registerProperties(registry)
        }
    }
}
