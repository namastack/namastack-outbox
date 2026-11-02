package io.namastack.outbox.schema

import io.namastack.outbox.Outbox
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxRecordStatus.NEW
import io.namastack.outbox.config.JdbcDatabaseType
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
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import org.springframework.stereotype.Component
import org.springframework.test.annotation.DirtiesContext
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.temporal.ChronoUnit.SECONDS
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@DirtiesContext
@TestInstance(PER_CLASS)
@SpringBootTest(
    classes = [TestApplication::class],
    properties = [
        "namastack.outbox.jdbc.schema-initialization.enabled=true",
        "namastack.outbox.polling.fixed.interval=300",
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

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Test
    fun `schema initialization creates outbox tables and processing works`() {
        assertThat(tableExists("outbox_record")).isTrue()
        assertThat(tableExists("outbox_instance")).isTrue()
        assertThat(tableExists("outbox_partition")).isTrue()

        transactionTemplate.executeWithoutResult { outbox.schedule("hello") }

        await().atMost(5, TimeUnit.SECONDS).untilAsserted {
            assertThat(outboxRecordRepository.findCompletedRecords()).hasSize(1)
        }
    }

    @Test
    fun `record age queries run against shipped schema`() {
        val createdAt = Instant.now().truncatedTo(SECONDS).minusSeconds(60)
        val nextRetryAt = createdAt.plusSeconds(3600)
        transactionTemplate.executeWithoutResult {
            outboxRecordRepository.save(
                OutboxRecord.restore(
                    id = UUID.randomUUID().toString(),
                    recordKey = UUID.randomUUID().toString(),
                    payload = "payload",
                    context = emptyMap(),
                    partition = AGE_QUERY_PARTITION,
                    createdAt = createdAt,
                    status = NEW,
                    completedAt = null,
                    failureCount = 0,
                    failureReason = null,
                    nextRetryAt = nextRetryAt,
                    handlerId = "handlerId",
                    failureException = null,
                ),
            )
        }

        assertThat(outboxRecordRepository.findOldestCreatedAtByPartitions(setOf(AGE_QUERY_PARTITION), NEW))
            .isEqualTo(createdAt)
        listOf(true, false).forEach { ignoreRecordKeysWithPreviousFailure ->
            assertThat(
                outboxRecordRepository.findOldestDueNextRetryAtByPartitions(
                    partitions = setOf(AGE_QUERY_PARTITION),
                    status = NEW,
                    now = nextRetryAt,
                    ignoreRecordKeysWithPreviousFailure = ignoreRecordKeysWithPreviousFailure,
                ),
            ).isEqualTo(nextRetryAt)
            assertThat(
                outboxRecordRepository.findOldestDueNextRetryAtByPartitions(
                    partitions = setOf(AGE_QUERY_PARTITION),
                    status = NEW,
                    now = nextRetryAt.minusSeconds(1),
                    ignoreRecordKeysWithPreviousFailure = ignoreRecordKeysWithPreviousFailure,
                ),
            ).isNull()
        }
    }

    @Test
    fun `schema initialization adds missing processing indexes without dropping existing indexes or records`() {
        val type = checkNotNull(JdbcDatabaseType.detect(dataSource))
        val indexName =
            when (type) {
                JdbcDatabaseType.PostgreSQL -> "idx_outbox_record_key_incomplete_ordered"
                JdbcDatabaseType.MySQL, JdbcDatabaseType.MariaDB -> "idx_outbox_record_completed_partition"
                JdbcDatabaseType.SQLServer -> "idx_outbox_record_incomplete_partition"
                JdbcDatabaseType.Oracle -> "idx_outbox_rec_completed_part"
                JdbcDatabaseType.H2 -> "idx_outbox_record_completed_key_ordered"
            }
        val jdbc = JdbcTemplate(dataSource)
        val dropSuffix =
            if (type in
                setOf(JdbcDatabaseType.MySQL, JdbcDatabaseType.MariaDB, JdbcDatabaseType.SQLServer)
            ) {
                " ON outbox_record"
            } else {
                ""
            }
        jdbc.execute("DROP INDEX $indexName$dropSuffix")
        val previousIndexes = recordIndexes()
        val id = UUID.randomUUID().toString()
        val now = Instant.now().truncatedTo(SECONDS)
        transactionTemplate.executeWithoutResult {
            outboxRecordRepository.create(
                OutboxRecord.restore(
                    id = id,
                    recordKey = id,
                    payload = "unchanged",
                    context = emptyMap(),
                    partition = AGE_QUERY_PARTITION,
                    createdAt = now,
                    status = NEW,
                    completedAt = null,
                    failureCount = 0,
                    failureReason = null,
                    nextRetryAt = now.plusSeconds(86400),
                    handlerId = "handlerId",
                    failureException = null,
                ),
            )
        }
        try {
            val before = jdbc.queryForMap("SELECT * FROM outbox_record WHERE id = ?", id)
            repeat(2) {
                ResourceDatabasePopulator()
                    .apply {
                        setSeparator(type.statementSeparator)
                        addScript(ClassPathResource(type.schemaLocation.removePrefix("classpath:")))
                    }.execute(dataSource)
                assertThat(recordIndexes()).containsAll(previousIndexes).contains(indexName.lowercase())
                assertThat(jdbc.queryForMap("SELECT * FROM outbox_record WHERE id = ?", id)).isEqualTo(before)
            }
        } finally {
            outboxRecordRepository.deleteById(id)
        }
    }

    private fun recordIndexes(): Set<String> =
        dataSource.connection.use { connection ->
            val tableName = if (connection.metaData.storesUpperCaseIdentifiers()) "OUTBOX_RECORD" else "outbox_record"
            connection.metaData
                .getIndexInfo(connection.catalog, connection.schema, tableName, false, false)
                .use { result ->
                    buildSet { while (result.next()) result.getString("INDEX_NAME")?.let { add(it.lowercase()) } }
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

    @SpringBootApplication
    class TestApplication

    companion object {
        private const val AGE_QUERY_PARTITION = 42
    }

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
