package io.namastack.outbox.schema

import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

class JpaSchemaValidationH2IntegrationTest : AbstractJpaSchemaValidationIntegrationTest() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.sql.init.schema-locations") { "classpath:schema/h2/outbox-tables.sql" }
            registry.add("spring.sql.init.mode") { "always" }
        }
    }
}
