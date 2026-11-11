package io.namastack.outbox.schema

import io.namastack.outbox.JdbcTestDatabase.POSTGRES
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@ResourceLock("jdbc-postgres")
class JdbcSchemaInitializationPostgresIntegrationTest : AbstractJdbcSchemaInitializationTest() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            POSTGRES.registerProperties(registry)
        }
    }
}
