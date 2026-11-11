package io.namastack.outbox.schema

import io.namastack.outbox.JdbcTestDatabase.MYSQL
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@ResourceLock("jdbc-mysql")
class JdbcSchemaInitializationMySqlIntegrationTest : AbstractJdbcSchemaInitializationTest() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            MYSQL.registerProperties(registry)
        }
    }
}
