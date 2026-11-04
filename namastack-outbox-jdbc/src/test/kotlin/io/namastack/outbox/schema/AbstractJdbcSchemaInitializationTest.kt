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
    fun `schema initialization can be repeated without changing existing indexes or records`() {
        val type = dataSource.connection.use { JdbcDatabaseType.from(it.metaData.databaseProductName) }
        val jdbc = JdbcTemplate(dataSource)
        val dropSuffix =
            if (type in
                setOf(JdbcDatabaseType.MySQL, JdbcDatabaseType.MariaDB, JdbcDatabaseType.SQLServer)
            ) {
                " ON outbox_record"
            } else {
                ""
            }
        // An upgraded installation may still have an index omitted from the fresh schema.
        val legacyStatusIndex =
            if (type == JdbcDatabaseType.Oracle) "idx_outbox_rec_stat" else "idx_outbox_record_status"
        val addedLegacyStatusIndex = legacyStatusIndex !in recordIndexes()
        if (addedLegacyStatusIndex) {
            jdbc.execute("CREATE INDEX $legacyStatusIndex ON outbox_record (status)")
        }
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
                assertThat(recordIndexes()).containsExactlyInAnyOrderElementsOf(previousIndexes)
                assertThat(jdbc.queryForMap("SELECT * FROM outbox_record WHERE id = ?", id)).isEqualTo(before)
            }
        } finally {
            outboxRecordRepository.deleteById(id)
            if (addedLegacyStatusIndex) jdbc.execute("DROP INDEX $legacyStatusIndex$dropSuffix")
        }
    }

    @Test
    fun `fresh schema includes read indexes and omits redundant indexes`() {
        val type = dataSource.connection.use { JdbcDatabaseType.from(it.metaData.databaseProductName) }
        assertThat(recordIndexes()).containsAll(processingIndexNames(type))
        ageIndexName(type)?.let { assertThat(recordIndexes()).contains(it) }
        if (type !in setOf(JdbcDatabaseType.H2, JdbcDatabaseType.Oracle)) {
            assertThat(recordIndexes()).doesNotContain("idx_outbox_record_status")
        }
        val instanceStatusIndex =
            if (type == JdbcDatabaseType.Oracle) "idx_outbox_inst_stat" else "idx_outbox_instance_status"
        assertThat(tableIndexes("outbox_instance")).doesNotContain(instanceStatusIndex)
        if (type == JdbcDatabaseType.Oracle) {
            assertThat(recordIndexes())
                .contains("idx_outbox_rec_key_comp_order")
                .doesNotContain("idx_outbox_rec_k_c_c")
        }
    }

    private fun processingIndexNames(type: JdbcDatabaseType): Set<String> =
        when (type) {
            JdbcDatabaseType.PostgreSQL ->
                setOf(
                    "idx_outbox_record_key_incomplete_ordered",
                    "idx_outbox_record_status_retry_incomplete",
                    "idx_outbox_record_status_partition",
                    "idx_outbox_record_key_status",
                    "idx_outbox_record_completed_retention",
                )

            JdbcDatabaseType.MySQL ->
                setOf("idx_outbox_record_completed_partition", "idx_outbox_record_partition_keys")

            JdbcDatabaseType.MariaDB -> setOf("idx_outbox_record_completed_partition")

            JdbcDatabaseType.SQLServer ->
                setOf(
                    "idx_outbox_record_incomplete_partition",
                    "idx_outbox_record_key_incomplete_ordered",
                    "idx_outbox_record_completed_at",
                )

            JdbcDatabaseType.Oracle -> setOf("idx_outbox_rec_completed_part", "idx_outbox_rec_key_comp_order")

            JdbcDatabaseType.H2 -> setOf("idx_outbox_record_completed_key_ordered")
        }

    private fun ageIndexName(type: JdbcDatabaseType): String? =
        when (type) {
            JdbcDatabaseType.PostgreSQL, JdbcDatabaseType.SQLServer -> "idx_outbox_record_status_created"

            JdbcDatabaseType.MySQL, JdbcDatabaseType.MariaDB, JdbcDatabaseType.H2 ->
                "idx_outbox_record_partition_created"

            JdbcDatabaseType.Oracle -> null
        }

    private fun recordIndexes(): Set<String> = tableIndexes("outbox_record")

    private fun tableIndexes(table: String): Set<String> =
        dataSource.connection.use { connection ->
            val tableName = if (connection.metaData.storesUpperCaseIdentifiers()) table.uppercase() else table
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
