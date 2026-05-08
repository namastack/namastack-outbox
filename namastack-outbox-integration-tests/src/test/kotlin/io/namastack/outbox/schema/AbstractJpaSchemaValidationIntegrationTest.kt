package io.namastack.outbox.schema

import io.namastack.outbox.config.JdbcOutboxAutoConfiguration
import io.namastack.outbox.config.JdbcOutboxSchemaAutoConfiguration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext

@DirtiesContext
@TestInstance(PER_CLASS)
@SpringBootTest(
    classes = [AbstractJpaSchemaValidationIntegrationTest.TestApplication::class],
    properties = ["spring.jpa.hibernate.ddl-auto=validate"],
)
abstract class AbstractJpaSchemaValidationIntegrationTest {
    @Test
    fun `JPA entity validates successfully against shipped DDL schema`() {
        // Successful context startup with ddl-auto=validate is the assertion.
        // A SchemaManagementException on startup would fail this test before reaching here.
    }

    @SpringBootApplication(exclude = [JdbcOutboxAutoConfiguration::class, JdbcOutboxSchemaAutoConfiguration::class])
    class TestApplication
}
