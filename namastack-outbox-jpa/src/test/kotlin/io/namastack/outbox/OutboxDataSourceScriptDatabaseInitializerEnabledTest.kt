package io.namastack.outbox

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.sql.Connection
import javax.sql.DataSource

@DataJpaTest
@ImportAutoConfiguration(JpaOutboxAutoConfiguration::class)
class OutboxDataSourceScriptDatabaseInitializerEnabledTest {
    @Autowired
    private lateinit var dataSource: DataSource

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun dynamicProperties(registry: DynamicPropertyRegistry) {
            registry.add("outbox.schema-initialization.enabled") { "true" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "none" }
        }
    }

    @Test
    fun `initializer creates outbox tables`() {
        dataSource.connection.use { conn ->
            assertThat(findAllTableNames(conn)).contains("OUTBOX_RECORD")
        }
    }

    private fun findAllTableNames(connection: Connection): List<String> {
        connection.use { conn ->
            val tables = conn.metaData.getTables(null, null, "%", arrayOf("TABLE"))
            return generateSequence { if (tables.next()) tables.getString("TABLE_NAME") else null }
                .map { it.uppercase() }
                .toList()
        }
    }

    @EnableOutbox
    @SpringBootApplication
    class TestApplication
}
