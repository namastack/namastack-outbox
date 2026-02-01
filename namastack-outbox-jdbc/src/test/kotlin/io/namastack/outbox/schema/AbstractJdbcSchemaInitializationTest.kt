package io.namastack.outbox.schema

import io.namastack.outbox.Outbox
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.handler.OutboxHandler
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.schema.AbstractJdbcSchemaInitializationTest.MyHandler
import io.namastack.outbox.schema.AbstractJdbcSchemaInitializationTest.TestApplication
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.stereotype.Component
import org.springframework.test.annotation.DirtiesContext
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@DirtiesContext
@TestInstance(PER_CLASS)
@SpringBootTest(
    classes = [TestApplication::class],
    properties = [
        "namastack.outbox.jdbc.schema-initialization.enabled=true",
        "namastack.outbox.instance.graceful-shutdown-timeout-seconds=0",
        "namastack.outbox.poll-interval=300",
    ],
)
@Import(MyHandler::class)
abstract class AbstractJdbcSchemaInitializationTest {
    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var outbox: Outbox

    @Autowired
    private lateinit var outboxRecordRepository: OutboxRecordRepository

    @Test
    fun `schema initialization creates outbox tables and processing works`() {
        assertThat(tableExists("outbox_record")).isTrue()
        assertThat(tableExists("outbox_instance")).isTrue()
        assertThat(tableExists("outbox_partition")).isTrue()

        outbox.schedule("hello")

        await().atMost(5, TimeUnit.SECONDS).untilAsserted {
            assertThat(outboxRecordRepository.findCompletedRecords()).hasSize(1)
        }
    }

    private fun tableExists(tableName: String): Boolean {
        dataSource.connection.use { connection ->
            val meta = connection.metaData
            meta.getTables(null, null, "%", arrayOf("TABLE")).use { rs ->
                while (rs.next()) {
                    val name = rs.getString("TABLE_NAME") ?: continue
                    if (name.equals(tableName, ignoreCase = true)) {
                        return true
                    }
                }
            }
        }

        return false
    }

    @EnableScheduling
    @SpringBootApplication
    class TestApplication

    @Component
    class MyHandler : OutboxHandler {
        override fun handle(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) {
            // no-op; only needed to mark a record as processed
        }
    }
}
