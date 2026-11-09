package io.namastack.outbox

import io.namastack.outbox.OutboxRecordStatus.COMPLETED
import io.namastack.outbox.OutboxRecordStatus.FAILED
import io.namastack.outbox.OutboxRecordStatus.NEW
import io.namastack.outbox.completion.OutboxCompletion
import io.namastack.outbox.config.JdbcDatabaseType
import io.namastack.outbox.config.JdbcOutboxAutoConfiguration
import io.namastack.outbox.config.JdbcOutboxSchemaAutoConfiguration
import io.namastack.outbox.query.JdbcOutboxRecordQueriesFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ContextConfiguration
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset.UTC
import javax.sql.DataSource

/**
 * Shared JDBC repository integration checks across database query implementations.
 *
 * @author Aleksander Zamojski
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(
    JdbcClientAutoConfiguration::class,
    JdbcOutboxAutoConfiguration::class,
    JdbcOutboxSchemaAutoConfiguration::class,
    OutboxJacksonAutoConfiguration::class,
)
@ContextConfiguration(classes = [JdbcOutboxRecordRepositoryTest.TestApplication::class])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class AbstractJdbcOutboxRecordRepositoryIntegrationTest {
    private val now = Instant.parse("2026-01-01T12:00:00Z")
    private val clock = Clock.fixed(now, UTC)
    private val serializer = CountingSerializer()
    private lateinit var repository: OutboxRecordRepository

    @Autowired
    private lateinit var source: DataSource

    @Autowired
    private lateinit var transactions: TransactionTemplate

    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var tableNameResolver: JdbcTableNameResolver

    @BeforeEach
    fun setUp() {
        repository = createRepository(source, transactions, serializer, clock)
    }

    protected open fun createRepository(
        source: DataSource,
        transactions: TransactionTemplate,
        serializer: OutboxPayloadSerializer,
        clock: Clock,
    ): OutboxRecordRepository =
        JdbcOutboxPersistenceFactory
            .create(source, transactions.transactionManager!!, serializer, clock)
            .recordRepository

    @Test
    fun `findIncompleteRecordPages keeps key boundaries and orders by creation time`() {
        create("a3", "a", now.plusSeconds(1))
        create("b1", "b")
        create("a2", "a", now.minusSeconds(1))
        create("a1", "a", now.minusSeconds(2))

        val pages = repository.findIncompleteRecordPages(setOf("b", "a"))

        assertThat(pages.keys).containsExactlyInAnyOrder("a", "b")
        assertThat(pages.getValue("a").recordIds()).containsExactly("a1", "a2", "a3")
        assertThat(pages.getValue("b").recordIds()).containsExactly("b1")
    }

    @Test
    fun `findIncompleteRecordPages orders records with equal creation time by id`() {
        create("c", "a")
        create("a", "a")
        create("b", "a")

        assertThat(repository.findIncompleteRecordPages(setOf("a")).getValue("a").recordIds())
            .containsExactly("a", "b", "c")
    }

    @Test
    fun `findIncompleteRecordPages returns an empty page for a key without records`() {
        assertThat(repository.findIncompleteRecordPages(setOf("missing")).getValue("missing").isEmpty()).isTrue()
    }

    @Test
    fun `findIncompleteRecordPages returns the same order as findIncompleteRecordsByRecordKey`() {
        create("a3", "a", now.plusSeconds(1))
        create("a2", "a")
        create("a1", "a")

        assertThat(repository.findIncompleteRecordPages(setOf("a")).getValue("a").recordIds())
            .containsExactlyElementsOf(repository.findIncompleteRecordsByRecordKey("a").map { it.id })
    }

    @Test
    fun `findIncompleteRecordPages returns future retries and excludes completed and failed records`() {
        create("a1", "a")
        create("a2", "a", now.plusSeconds(1), retry = now.plusSeconds(3600))
        create("a3", "a", now.plusSeconds(2))
        create("completed", "a", status = COMPLETED)
        create("failed", "a", status = FAILED)

        val page = repository.findIncompleteRecordPages(setOf("a")).getValue("a")

        assertThat(page.recordIds()).containsExactly("a1", "a2", "a3")
        assertThat(page[1].nextRetryAt).isEqualTo(now.plusSeconds(3600))
    }

    @Test
    fun `findIncompleteRecordPages decodes a record only on first access`() {
        create("a1", "a")
        serializer.reset()

        val page = repository.findIncompleteRecordPages(setOf("a")).getValue("a")

        assertThat(page.size).isEqualTo(1)
        assertThat(serializer.deserializations).isZero()
        assertThat(serializer.serializations).isZero()

        val record = page[0]
        assertThat(record.id).isEqualTo("a1")
        assertThat(record.nextRetryAt).isEqualTo(now)
        assertThat(record.payload).isEqualTo("payload-a1")
        assertThat(record.context).containsEntry("trace", "original")
        assertThat(serializer.deserializations).isEqualTo(2)
    }

    @Test
    fun `findIncompleteRecordPages caches a decoded record with its mutations`() {
        create("a1", "a")
        val page = repository.findIncompleteRecordPages(setOf("a")).getValue("a")
        val record = page[0]
        assertThat(record.context).containsEntry("trace", "original")
        val deserializations = serializer.deserializations

        (record.context as MutableMap<String, String>)["trace"] = "mutated"

        assertThat(page[0]).isSameAs(record)
        assertThat(page[0].context).containsEntry("trace", "mutated")
        assertThat(serializer.deserializations).isEqualTo(deserializations)
    }

    @Test
    fun `findIncompleteRecordPages loads pages with an unavailable payload type and fails on its access`() {
        create("bad", "a", retry = now.plusSeconds(60))
        create("good", "b")
        jdbc.sql("UPDATE outbox_record SET record_type = 'missing.Payload' WHERE id = 'bad'").update()
        serializer.reset()

        val pages = repository.findIncompleteRecordPages(setOf("a", "b"))

        assertThat(pages.getValue("a").size).isEqualTo(1)
        assertThat(serializer.deserializations).isZero()
        assertThat(pages.getValue("b")[0].id).isEqualTo("good")
        assertThatThrownBy { pages.getValue("a")[0] }
            .isInstanceOf(OutboxPayloadTypeNotFoundException::class.java)
    }

    @Test
    fun `findIncompleteRecordPages keeps the valid prefix readable before a malformed context`() {
        create("a1", "a")
        create("a2", "a", now.plusSeconds(1))
        jdbc.sql("UPDATE outbox_record SET context = 'not-json' WHERE id = 'a2'").update()

        val page = repository.findIncompleteRecordPages(setOf("a")).getValue("a")

        assertThat(page[0].payload).isEqualTo("payload-a1")
        assertThat(page.size).isEqualTo(2)
        assertThatThrownBy { page[1] }.isInstanceOf(Exception::class.java)
    }

    @Test
    fun `findIncompleteRecordPages keeps an earlier page stable and the next read sees progress`() {
        create("a1", "a")
        val earlierPage = repository.findIncompleteRecordPages(setOf("a")).getValue("a")
        create("a2", "a", now.plusSeconds(1))
        jdbc
            .sql("UPDATE outbox_record SET status = 'COMPLETED', completed_at = :now WHERE id = 'a1'")
            .param("now", java.sql.Timestamp.from(now))
            .update()

        assertThat(earlierPage.recordIds()).containsExactly("a1")
        assertThat(repository.findIncompleteRecordPages(setOf("a")).getValue("a").recordIds())
            .containsExactly("a2")
    }

    @Test
    fun `findIncompleteRecordPages returns no pages for an empty request`() {
        assertThat(repository.findIncompleteRecordPages(emptySet())).isEmpty()
    }

    @Test
    fun `findIncompleteRecordPages accepts 1000 keys and rejects more`() {
        assertThat(repository.findIncompleteRecordPages((1..1000).map { "missing-$it" }.toSet())).hasSize(1000)
        assertThatThrownBy { repository.findIncompleteRecordPages((1..1001).map { "missing-$it" }.toSet()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `findIncompleteRecordPages returns an empty page for a selected key that was deleted`() {
        create("remaining", "remaining")
        create("disappeared", "disappeared")
        val selected = repository.findRecordKeysInPartitions(setOf(1), 10, true).toSet()
        repository.deleteById("disappeared")

        val pages = repository.findIncompleteRecordPages(selected)

        assertThat(pages.getValue("remaining").recordIds()).containsExactly("remaining")
        assertThat(pages.getValue("disappeared").isEmpty()).isTrue()
    }

    @Test
    fun `findIncompleteRecordsByRecordKey orders records with equal creation time by id`() {
        create("c", "a")
        create("a", "a")
        create("b", "a")

        assertThat(repository.findIncompleteRecordsByRecordKey("a").map { it.id }).containsExactly("a", "b", "c")
    }

    @Test
    fun `findIncompleteRecordsByRecordKey returns future retries and excludes completed and failed records`() {
        create("n1", "a", now.minusSeconds(2))
        create("n2", "a", now.minusSeconds(1), retry = now.plusSeconds(3600))
        create("c1", "a", now.minusSeconds(3), status = COMPLETED)
        create("f1", "a", now.minusSeconds(4), status = FAILED)

        assertThat(repository.findIncompleteRecordsByRecordKey("a").map { it.id }).containsExactly("n1", "n2")
    }

    @Test
    fun `findPendingRecords returns new records oldest first`() {
        create("n2", "a")
        create("n1", "b", now.minusSeconds(10))
        create("f1", "c", status = FAILED)
        create("c1", "d", status = COMPLETED)

        assertThat(repository.findPendingRecords().map { it.id }).containsExactly("n1", "n2")
    }

    @Test
    fun `findRecordKeysInPartitions in strict mode returns keys whose first incomplete record is due in order`() {
        seedHeadOfKeyScenario()

        assertThat(repository.findRecordKeysInPartitions(setOf(1), 10, true))
            .containsExactly("tie-z", "tie-a", "a", "d")
    }

    @Test
    fun `findRecordKeysInPartitions in strict mode honours the batch size`() {
        seedHeadOfKeyScenario()

        assertThat(repository.findRecordKeysInPartitions(setOf(1), 3, true))
            .containsExactly("tie-z", "tie-a", "a")
    }

    @Test
    fun `findRecordKeysInPartitions in strict mode is blocked by an earlier equal time record`() {
        create("a1", "a", now.minusSeconds(5), retry = now.plusSeconds(3600))
        create("a2", "a", now.minusSeconds(5))

        assertThat(repository.findRecordKeysInPartitions(setOf(1), 10, true)).isEmpty()
    }

    @Test
    fun `findRecordKeysInPartitions in non strict mode orders keys by their first due record`() {
        seedHeadOfKeyScenario()

        assertThat(repository.findRecordKeysInPartitions(setOf(1), 10, false))
            .containsExactly("tie-z", "tie-a", "a", "d", "b", "c")
    }

    @Test
    fun `findRecordKeysInPartitions in non strict mode honours the batch size`() {
        seedHeadOfKeyScenario()

        assertThat(repository.findRecordKeysInPartitions(setOf(1), 5, false))
            .containsExactly("tie-z", "tie-a", "a", "d", "b")
    }

    @Test
    fun `findRecordKeysInPartitions in non strict mode is not blocked by an earlier record that is not due`() {
        create("a1", "a", now.minusSeconds(5), retry = now.plusSeconds(3600))
        create("a2", "a", now.minusSeconds(5))

        assertThat(repository.findRecordKeysInPartitions(setOf(1), 10, false)).containsExactly("a")
    }

    @Test
    fun `findRecordKeysInPartitions returns nothing for partitions without records`() {
        seedHeadOfKeyScenario()

        listOf(true, false).forEach { strict ->
            assertThat(repository.findRecordKeysInPartitions(setOf(2, 3), 10, strict)).isEmpty()
        }
    }

    @Test
    fun `findRecordKeysInPartitions returns nothing without records`() {
        listOf(true, false).forEach { strict ->
            assertThat(repository.findRecordKeysInPartitions(setOf(1), 10, strict)).isEmpty()
        }
    }

    @Test
    fun `countByStatus counts only the requested status`() {
        create("n1", "a")
        create("n2", "b")
        create("f1", "c", status = FAILED)
        create("c1", "d", status = COMPLETED)

        assertThat(repository.countByStatus(NEW)).isEqualTo(2)
        assertThat(repository.countByStatus(FAILED)).isEqualTo(1)
        assertThat(repository.countByStatus(COMPLETED)).isEqualTo(1)
    }

    @Test
    fun `countByStatuses counts incomplete statuses`() {
        create("n1", "a")
        create("n2", "b")
        create("f1", "c", status = FAILED)
        create("c1", "d", status = COMPLETED)

        assertThat(repository.countByStatuses(setOf(NEW, FAILED)))
            .containsExactlyInAnyOrderEntriesOf(mapOf(NEW to 2L, FAILED to 1L))
    }

    @Test
    fun `countByStatuses counts completed alone`() {
        create("n1", "a")
        create("c1", "b", status = COMPLETED)
        create("c2", "c", status = COMPLETED)

        assertThat(repository.countByStatuses(setOf(COMPLETED))).containsExactlyEntriesOf(mapOf(COMPLETED to 2L))
    }

    @Test
    fun `countByStatuses counts completed mixed with incomplete statuses`() {
        create("n1", "a")
        create("f1", "b", status = FAILED)
        create("c1", "c", status = COMPLETED)
        create("c2", "d", status = COMPLETED)

        assertThat(repository.countByStatuses(setOf(NEW, COMPLETED)))
            .containsExactlyInAnyOrderEntriesOf(mapOf(NEW to 1L, COMPLETED to 2L))
        assertThat(repository.countByStatuses(OutboxRecordStatus.entries.toSet()))
            .containsExactlyInAnyOrderEntriesOf(mapOf(NEW to 1L, FAILED to 1L, COMPLETED to 2L))
    }

    @Test
    fun `countByStatuses returns zero for requested statuses without records`() {
        create("n1", "a")

        assertThat(repository.countByStatuses(setOf(COMPLETED))).containsExactlyEntriesOf(mapOf(COMPLETED to 0L))
        assertThat(repository.countByStatuses(setOf(NEW, FAILED)))
            .containsExactlyInAnyOrderEntriesOf(mapOf(NEW to 1L, FAILED to 0L))
    }

    @Test
    fun `countByStatuses returns nothing when no status is requested`() {
        assertThat(repository.countByStatuses(emptySet())).isEmpty()
    }

    @Test
    fun `countRecordsByPartition counts one partition and status`() {
        create("n1", "a", partition = 1)
        create("n2", "b", partition = 2)
        create("f1", "c", partition = 1, status = FAILED)

        assertThat(repository.countRecordsByPartition(1, NEW)).isEqualTo(1)
        assertThat(repository.countRecordsByPartition(2, NEW)).isEqualTo(1)
        assertThat(repository.countRecordsByPartition(2, FAILED)).isZero()
    }

    @Test
    fun `countRecordsByPartitions counts every status across the partitions`() {
        create("n1", "a", partition = 1)
        create("n2", "b", partition = 2)
        create("n3", "c", partition = 3)
        create("f1", "d", partition = 1, status = FAILED)
        create("c1", "e", partition = 1, status = COMPLETED)
        create("c2", "f", partition = 2, status = COMPLETED)

        assertThat(repository.countRecordsByPartitions(setOf(1, 2), NEW)).isEqualTo(2)
        assertThat(repository.countRecordsByPartitions(setOf(1, 2), FAILED)).isEqualTo(1)
        assertThat(repository.countRecordsByPartitions(setOf(1, 2), COMPLETED)).isEqualTo(2)
    }

    @Test
    fun `countRecordsByPartitions returns zero for partitions without records and for no partitions`() {
        create("c1", "a", partition = 1, status = COMPLETED)

        assertThat(repository.countRecordsByPartitions(setOf(2), COMPLETED)).isZero()
        assertThat(repository.countRecordsByPartitions(emptySet(), COMPLETED)).isZero()
    }

    @Test
    fun `findOldestCreatedAtByPartitions returns the oldest new record regardless of its retry time`() {
        seedHeadOfKeyScenario()

        assertThat(repository.findOldestCreatedAtByPartitions(setOf(1))).isEqualTo(now.minusSeconds(30))
    }

    @Test
    fun `findOldestCreatedAtByPartitions returns null for partitions without records`() {
        seedHeadOfKeyScenario()

        assertThat(repository.findOldestCreatedAtByPartitions(setOf(2, 3))).isNull()
    }

    @Test
    fun `findOldestCreatedAtByPartitions returns null without records`() {
        assertThat(repository.findOldestCreatedAtByPartitions(setOf(1))).isNull()
    }

    @Test
    fun `findOldestDueNextRetryAtByPartitions in strict mode skips blocked records`() {
        seedHeadOfKeyScenario()

        assertThat(repository.findOldestDueNextRetryAtByPartitions(setOf(1), now, true))
            .isEqualTo(now.minusSeconds(15))
    }

    @Test
    fun `findOldestDueNextRetryAtByPartitions in strict mode is blocked by an earlier equal time record`() {
        create("a1", "a", now.minusSeconds(5), retry = now.plusSeconds(3600))
        create("a2", "a", now.minusSeconds(5))

        assertThat(repository.findOldestDueNextRetryAtByPartitions(setOf(1), now, true)).isNull()
    }

    @Test
    fun `findOldestDueNextRetryAtByPartitions in non strict mode includes blocked records`() {
        seedHeadOfKeyScenario()

        assertThat(repository.findOldestDueNextRetryAtByPartitions(setOf(1), now, false))
            .isEqualTo(now.minusSeconds(50))
    }

    @Test
    fun `findOldestDueNextRetryAtByPartitions returns null when no record is due yet`() {
        seedHeadOfKeyScenario()

        listOf(true, false).forEach { strict ->
            assertThat(repository.findOldestDueNextRetryAtByPartitions(setOf(1), now.minusSeconds(60), strict)).isNull()
        }
    }

    @Test
    fun `findOldestDueNextRetryAtByPartitions returns null for partitions without records`() {
        seedHeadOfKeyScenario()

        listOf(true, false).forEach { strict ->
            assertThat(repository.findOldestDueNextRetryAtByPartitions(setOf(2, 3), now, strict)).isNull()
        }
    }

    @Test
    fun `findOldestDueNextRetryAtByPartitions returns null without records`() {
        listOf(true, false).forEach { strict ->
            assertThat(repository.findOldestDueNextRetryAtByPartitions(setOf(1), now, strict)).isNull()
        }
    }

    @Test
    fun `updateProcessingState persists the state without changing payload or context`() {
        val record = create("a1", "a")
        serializer.reset()

        val updated =
            repository.updateProcessingState(
                OutboxRecord.restore(
                    id = record.id,
                    recordKey = record.key,
                    payload = "changed",
                    context = mapOf("changed" to "true"),
                    partition = record.partition,
                    createdAt = record.createdAt,
                    status = COMPLETED,
                    completedAt = now,
                    failureCount = 2,
                    failureException = null,
                    failureReason = "boom",
                    nextRetryAt = now.plusSeconds(60),
                    handlerId = record.handlerId,
                ),
            )

        assertThat(updated).isTrue()
        assertThat(serializer.serializations).isZero()
        val stored = repository.findCompletedRecords().single()
        assertThat(stored.status).isEqualTo(COMPLETED)
        assertThat(stored.completedAt).isEqualTo(now)
        assertThat(stored.failureCount).isEqualTo(2)
        assertThat(stored.failureReason).isEqualTo("boom")
        assertThat(stored.nextRetryAt).isEqualTo(now.plusSeconds(60))
        assertThat(stored.payload).isEqualTo("payload-a1")
        assertThat(stored.context).isEqualTo(record.context)
        assertThat(stored.key).isEqualTo(record.key)
        assertThat(stored.handlerId).isEqualTo(record.handlerId)
        assertThat(stored.partition).isEqualTo(record.partition)
        assertThat(stored.createdAt).isEqualTo(record.createdAt)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `completion writer commits update and delete batches with the database driver`() {
        val ids = listOf("batch-update-1", "batch-update-2", "batch-delete-1", "batch-delete-2")
        try {
            ids.forEach { create(it, it) }
            val writer =
                JdbcOutboxCompletionBatchWriter(NamedParameterJdbcTemplate(source), transactions) {
                    JdbcOutboxRecordQueriesFactory.create(
                        JdbcDatabaseType.detect(source),
                        tableNameResolver.outboxRecord,
                    )
                }
            val updates =
                ids.take(2).map { id ->
                    OutboxCompletion.Update(id, COMPLETED, now, 0, null, now)
                }
            val deletions = ids.drop(2).map(OutboxCompletion::Delete)

            assertThat(writer.writeUpdates(updates)).allMatch { it.isSuccess }
            assertThat(writer.writeDeletions(deletions)).allMatch { it.isSuccess }
            assertThat(repository.findCompletedRecords().map { it.id }).containsAll(ids.take(2))
            assertThat(repository.countByStatus(NEW)).isZero()
        } finally {
            ids.forEach(repository::deleteById)
        }
    }

    @Test
    fun `findCompletedRecords returns at most the limit oldest first`() {
        seedRetentionScenario()

        assertThat(repository.findCompletedRecords(now, 2).map { it.id }).containsExactly("c1", "c2")
    }

    @Test
    fun `findFailedRecords returns at most the limit oldest first`() {
        seedRetentionScenario()

        assertThat(repository.findFailedRecords(now, 1).map { it.id }).containsExactly("f1")
    }

    @Test
    fun `deleteCompletedRecords deletes at most the limit oldest first`() {
        seedRetentionScenario()

        assertThat(repository.deleteCompletedRecords(now, 2)).isEqualTo(2)

        assertThat(repository.findCompletedRecords().map { it.id }).containsExactly("c3")
    }

    @Test
    fun `deleteFailedRecords deletes at most the limit oldest first`() {
        seedRetentionScenario()

        assertThat(repository.deleteFailedRecords(now, 1)).isEqualTo(1)

        assertThat(repository.findFailedRecords().map { it.id }).containsExactly("f2")
    }

    @Test
    fun `deleteByStatus deletes only the requested status`() {
        create("n1", "a")
        create("c1", "b", status = COMPLETED)

        repository.deleteByStatus(COMPLETED)

        assertThat(repository.findCompletedRecords()).isEmpty()
        assertThat(repository.findPendingRecords().map { it.id }).containsExactly("n1")
    }

    @Test
    fun `deleteByRecordKeyAndStatus deletes only the requested key and status`() {
        create("a1", "a")
        create("a2", "a", status = FAILED)
        create("b1", "b")

        repository.deleteByRecordKeyAndStatus("a", NEW)

        assertThat(repository.findPendingRecords().map { it.id }).containsExactly("b1")
        assertThat(repository.findFailedRecords().map { it.id }).containsExactly("a2")
    }

    @Test
    fun `deleteById deletes only the requested record`() {
        create("a1", "a")
        create("a2", "a", now.plusSeconds(1))

        repository.deleteById("a1")

        assertThat(repository.findPendingRecords().map { it.id }).containsExactly("a2")
    }

    @Test
    fun `deleteByIds deletes only the requested records and returns their count`() {
        create("a1", "a")
        create("a2", "b")
        create("a3", "c")

        assertThat(repository.deleteByIds(listOf("a1", "a2", "missing"))).isEqualTo(2)

        assertThat(repository.findPendingRecords().map { it.id }).containsExactly("a3")
    }

    /**
     * Seeds partition 1 with:
     * - `tie-z` and `tie-a`: equal creation time, ordered by id (`t-1` before `t-2`)
     * - `a`: due head; `d`: due head after a completed record
     * - `b`: failed head blocking a record due since `now - 50s`
     * - `c`: head not yet due blocking a later due record
     */
    private fun seedHeadOfKeyScenario() {
        create("t-2", "tie-a", now.minusSeconds(15), retry = now.minusSeconds(15))
        create("t-1", "tie-z", now.minusSeconds(15), retry = now.minusSeconds(15))
        create("a1", "a", now.minusSeconds(10), retry = now.minusSeconds(10))
        create("d1", "d", now.minusSeconds(40), status = COMPLETED)
        create("d2", "d", now.minusSeconds(8), retry = now.minusSeconds(8))
        create("b1", "b", now.minusSeconds(20), retry = now.minusSeconds(20), status = FAILED)
        create("b2", "b", now.minusSeconds(5), retry = now.minusSeconds(50))
        create("c1", "c", now.minusSeconds(30), retry = now.plusSeconds(3600))
        create("c2", "c", now.minusSeconds(1), retry = now.minusSeconds(1))
    }

    private fun seedRetentionScenario() {
        create("c3", "a", status = COMPLETED, completed = now.minusSeconds(10))
        create("c1", "b", now.plusSeconds(1), status = COMPLETED, completed = now.minusSeconds(30))
        create("c2", "c", now.plusSeconds(2), status = COMPLETED, completed = now.minusSeconds(20))
        create("f2", "d", retry = now.minusSeconds(20), status = FAILED)
        create("f1", "e", now.plusSeconds(1), retry = now.minusSeconds(30), status = FAILED)
    }

    private fun create(
        id: String,
        key: String,
        created: Instant = now,
        retry: Instant = now,
        status: OutboxRecordStatus = NEW,
        completed: Instant = now,
        partition: Int = 1,
    ): OutboxRecord<String> =
        repository.create(
            OutboxRecord.restore(
                id = id,
                recordKey = key,
                payload = "payload-$id",
                context = mapOf("trace" to "original"),
                partition = partition,
                createdAt = created,
                status = status,
                completedAt = if (status == COMPLETED) completed else null,
                failureCount = 0,
                failureException = null,
                failureReason = null,
                nextRetryAt = retry,
                handlerId = "handler",
            ),
        )

    /**
     * Counts serialization calls made by repository operations.
     *
     * @author Aleksander Zamojski
     */
    private class CountingSerializer : OutboxPayloadSerializer {
        private val delegate = JacksonOutboxPayloadSerializer(JsonMapper.builder().build())
        var serializations = 0
        var deserializations = 0

        override fun serialize(payload: Any): String {
            serializations++
            return delegate.serialize(payload)
        }

        override fun <T> deserialize(
            serialized: String,
            type: Class<T>,
        ): T {
            deserializations++
            return delegate.deserialize(serialized, type)
        }

        fun reset() {
            serializations = 0
            deserializations = 0
        }
    }

    private fun OutboxRecordPage.recordIds(): List<String> = (0 until size).map { get(it).id }
}
