package io.namastack.outbox

import io.namastack.outbox.config.JdbcOutboxAutoConfiguration
import io.namastack.outbox.config.JdbcOutboxSchemaAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ContextConfiguration
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset.UTC
import javax.sql.DataSource

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
abstract class AbstractJdbcBulkRecordLoadingTest {
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
    fun `pages retain exact key boundaries and creation time then id ordering`() {
        create("a3", "a", now.plusSeconds(1))
        create("b1", "b")
        create("a2", "a")
        create("a1", "a")
        val pages = repository.findIncompleteRecordPages(setOf("b", "a", "missing"))

        assertThat(pages.keys).containsExactlyInAnyOrder("a", "b", "missing")
        assertThat(pages.getValue("a").recordIds()).containsExactly("a1", "a2", "a3")
        assertThat(pages.getValue("b").recordIds()).containsExactly("b1")
        assertThat(pages.getValue("missing").isEmpty()).isTrue()
        assertThat(pages.getValue("a").recordIds())
            .containsExactlyElementsOf(repository.findIncompleteRecordsByRecordKey("a").map { it.id })
    }

    @Test
    fun `bulk read retains future retries and excludes completed and failed rows`() {
        create("a1", "a")
        create("a2", "a", now.plusSeconds(1), retry = now.plusSeconds(3600))
        create("a3", "a", now.plusSeconds(2))
        create("completed", "a", status = OutboxRecordStatus.COMPLETED)
        create("failed", "a", status = OutboxRecordStatus.FAILED)

        val page = repository.findIncompleteRecordPages(setOf("a")).getValue("a")

        assertThat(page.recordIds()).containsExactly("a1", "a2", "a3")
        assertThat(page[1].nextRetryAt).isEqualTo(now.plusSeconds(3600))
    }

    @Test
    fun `bulk loading defers decoding and cached records retain mutations`() {
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
        (record.context as MutableMap<String, String>)["trace"] = "mutated"
        assertThat(page[0]).isSameAs(record)
        assertThat(page[0].context).containsEntry("trace", "mutated")
        assertThat(serializer.deserializations).isEqualTo(2)
    }

    @Test
    fun `unavailable payload types do not prevent loading pages or reading another key`() {
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
    fun `later malformed context does not prevent decoding the valid prefix`() {
        create("a1", "a")
        create("a2", "a", now.plusSeconds(1))
        jdbc.sql("UPDATE outbox_record SET context = 'not-json' WHERE id = 'a2'").update()

        val page = repository.findIncompleteRecordPages(setOf("a")).getValue("a")

        assertThat(page[0].payload).isEqualTo("payload-a1")
        assertThat(page.size).isEqualTo(2)
        assertThatThrownBy { page[1] }.isInstanceOf(Exception::class.java)
    }

    @Test
    fun `next read excludes completed records and includes later inserts`() {
        create("a1", "a")
        val oldPage = repository.findIncompleteRecordPages(setOf("a")).getValue("a")
        create("a2", "a", now.plusSeconds(1))
        jdbc
            .sql("UPDATE outbox_record SET status = 'COMPLETED', completed_at = :now WHERE id = 'a1'")
            .param("now", java.sql.Timestamp.from(now))
            .update()

        assertThat(oldPage.recordIds()).containsExactly("a1")
        assertThat(repository.findIncompleteRecordPages(setOf("a")).getValue("a").recordIds())
            .containsExactly("a2")
    }

    @Test
    fun `empty requests return no pages and the portable key bound is enforced`() {
        assertThat(repository.findIncompleteRecordPages(emptySet())).isEmpty()
        assertThat(repository.findIncompleteRecordPages((1..1000).map { "missing-$it" }.toSet())).hasSize(1000)
        assertThatThrownBy { repository.findIncompleteRecordPages((1..1001).map { "missing-$it" }.toSet()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a selected key that disappears returns an empty page without another read`() {
        create("remaining", "remaining")
        create("disappeared", "disappeared")
        val selected = repository.findRecordKeysInPartitions(setOf(1), OutboxRecordStatus.NEW, 10, true).toSet()
        repository.deleteById("disappeared")
        val pages = repository.findIncompleteRecordPages(selected)

        assertThat(pages.getValue("remaining").recordIds()).containsExactly("remaining")
        assertThat(pages.getValue("disappeared").isEmpty()).isTrue()
    }

    @Test
    fun `key query returns keys whose first incomplete record is due in creation time then id order`() {
        seedHeadOfKeyScenario()

        assertThat(repository.findRecordKeysInPartitions(setOf(1), OutboxRecordStatus.NEW, 10, true))
            .containsExactly("tie-z", "tie-a", "a", "d")
        assertThat(repository.findRecordKeysInPartitions(setOf(1), OutboxRecordStatus.NEW, 3, true))
            .containsExactly("tie-z", "tie-a", "a")
    }

    @Test
    fun `key query without the previous failure filter orders keys by their first due record`() {
        seedHeadOfKeyScenario()

        assertThat(repository.findRecordKeysInPartitions(setOf(1), OutboxRecordStatus.NEW, 10, false))
            .containsExactly("tie-z", "tie-a", "a", "d", "b", "c")
        assertThat(repository.findRecordKeysInPartitions(setOf(1), OutboxRecordStatus.NEW, 5, false))
            .containsExactly("tie-z", "tie-a", "a", "d", "b")
    }

    @Test
    fun `key and age queries return nothing for partitions without records`() {
        seedHeadOfKeyScenario()

        listOf(true, false).forEach { filter ->
            assertThat(repository.findRecordKeysInPartitions(setOf(2, 3), OutboxRecordStatus.NEW, 10, filter)).isEmpty()
            assertThat(
                repository.findOldestDueNextRetryAtByPartitions(setOf(2, 3), OutboxRecordStatus.NEW, now, filter),
            ).isNull()
        }
        assertThat(repository.findOldestCreatedAtByPartitions(setOf(2, 3), OutboxRecordStatus.NEW)).isNull()
    }

    @Test
    fun `oldest due record skips blocked records only with the previous failure filter`() {
        seedHeadOfKeyScenario()

        assertThat(repository.findOldestDueNextRetryAtByPartitions(setOf(1), OutboxRecordStatus.NEW, now, true))
            .isEqualTo(now.minusSeconds(15))
        assertThat(repository.findOldestDueNextRetryAtByPartitions(setOf(1), OutboxRecordStatus.NEW, now, false))
            .isEqualTo(now.minusSeconds(50))
        assertThat(
            repository.findOldestDueNextRetryAtByPartitions(
                setOf(1),
                OutboxRecordStatus.NEW,
                now.minusSeconds(60),
                true,
            ),
        ).isNull()
    }

    @Test
    fun `oldest created time considers incomplete records with the status`() {
        seedHeadOfKeyScenario()

        assertThat(repository.findOldestCreatedAtByPartitions(setOf(1), OutboxRecordStatus.NEW))
            .isEqualTo(now.minusSeconds(30))
    }

    @Test
    fun `grouped status counts include requested statuses without records`() {
        create("n1", "a")
        create("n2", "b")
        create("f1", "c", status = OutboxRecordStatus.FAILED)

        assertThat(repository.countByStatuses(setOf(OutboxRecordStatus.NEW, OutboxRecordStatus.FAILED)))
            .containsExactlyInAnyOrderEntriesOf(mapOf(OutboxRecordStatus.NEW to 2L, OutboxRecordStatus.FAILED to 1L))
        assertThat(repository.countByStatuses(setOf(OutboxRecordStatus.COMPLETED)))
            .containsExactlyEntriesOf(mapOf(OutboxRecordStatus.COMPLETED to 0L))
        assertThat(repository.countByStatuses(emptySet())).isEmpty()
    }

    @Test
    fun `cleanup reads and deletes at most the limit oldest first`() {
        create("c3", "a", status = OutboxRecordStatus.COMPLETED, completed = now.minusSeconds(10))
        create("c1", "b", status = OutboxRecordStatus.COMPLETED, completed = now.minusSeconds(30))
        create("c2", "c", status = OutboxRecordStatus.COMPLETED, completed = now.minusSeconds(20))
        create("f2", "d", retry = now.minusSeconds(20), status = OutboxRecordStatus.FAILED)
        create("f1", "e", retry = now.minusSeconds(30), status = OutboxRecordStatus.FAILED)

        assertThat(repository.findCompletedRecords(now, 2).map { it.id }).containsExactly("c1", "c2")
        assertThat(repository.findFailedRecords(now, 1).map { it.id }).containsExactly("f1")
        assertThat(repository.deleteCompletedRecords(now, 2)).isEqualTo(2)
        assertThat(repository.findCompletedRecords().map { it.id }).containsExactly("c3")
        assertThat(repository.deleteFailedRecords(now, 1)).isEqualTo(1)
        assertThat(repository.findFailedRecords().map { it.id }).containsExactly("f2")
    }

    @Test
    fun `completed records are counted alone and mixed with incomplete statuses`() {
        create("n1", "a", now.plusSeconds(1))
        create("f1", "b", status = OutboxRecordStatus.FAILED)
        create("c1", "c", status = OutboxRecordStatus.COMPLETED)
        create("c2", "d", status = OutboxRecordStatus.COMPLETED)

        assertThat(repository.countByStatuses(setOf(OutboxRecordStatus.COMPLETED)))
            .containsExactlyEntriesOf(mapOf(OutboxRecordStatus.COMPLETED to 2L))
        assertThat(repository.countByStatuses(setOf(OutboxRecordStatus.NEW, OutboxRecordStatus.COMPLETED)))
            .containsExactlyInAnyOrderEntriesOf(mapOf(OutboxRecordStatus.NEW to 1L, OutboxRecordStatus.COMPLETED to 2L))
        assertThat(repository.countByStatuses(OutboxRecordStatus.entries.toSet()))
            .containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    OutboxRecordStatus.NEW to 1L,
                    OutboxRecordStatus.FAILED to 1L,
                    OutboxRecordStatus.COMPLETED to 2L,
                ),
            )
        assertThat(repository.countRecordsByPartitions(setOf(1), OutboxRecordStatus.COMPLETED)).isEqualTo(2)
        assertThat(repository.countRecordsByPartitions(setOf(1), OutboxRecordStatus.NEW)).isEqualTo(1)
        assertThat(repository.countRecordsByPartitions(setOf(1), OutboxRecordStatus.FAILED)).isEqualTo(1)
        assertThat(repository.countRecordsByPartitions(setOf(2), OutboxRecordStatus.COMPLETED)).isZero()
        assertThat(repository.countRecordsByPartitions(emptySet(), OutboxRecordStatus.COMPLETED)).isZero()
    }

    @Test
    fun `incomplete record reads return nothing for completed status`() {
        create("c1", "a", status = OutboxRecordStatus.COMPLETED)

        listOf(true, false).forEach { filter ->
            assertThat(repository.findRecordKeysInPartitions(setOf(1), OutboxRecordStatus.COMPLETED, 10, filter))
                .isEmpty()
            assertThat(
                repository.findOldestDueNextRetryAtByPartitions(setOf(1), OutboxRecordStatus.COMPLETED, now, filter),
            ).isNull()
        }
        assertThat(repository.findOldestCreatedAtByPartitions(setOf(1), OutboxRecordStatus.COMPLETED)).isNull()
    }

    @Test
    fun `incomplete record reads return failed records`() {
        create("f1", "a", now.minusSeconds(10), retry = now.minusSeconds(5), status = OutboxRecordStatus.FAILED)
        create("n1", "b", now.minusSeconds(20))

        assertThat(repository.findRecordKeysInPartitions(setOf(1), OutboxRecordStatus.FAILED, 10, true))
            .containsExactly("a")
        assertThat(repository.findRecordKeysInPartitions(setOf(1), OutboxRecordStatus.FAILED, 10, false))
            .containsExactly("a")
        assertThat(repository.findOldestCreatedAtByPartitions(setOf(1), OutboxRecordStatus.FAILED))
            .isEqualTo(now.minusSeconds(10))
        assertThat(repository.findOldestDueNextRetryAtByPartitions(setOf(1), OutboxRecordStatus.FAILED, now, false))
            .isEqualTo(now.minusSeconds(5))
    }

    @Test
    fun `records with equal creation time keep id order in pages and per key reads`() {
        create("c", "a")
        create("a", "a")
        create("b", "a")

        assertThat(repository.findIncompleteRecordPages(setOf("a")).getValue("a").recordIds())
            .containsExactly("a", "b", "c")
        assertThat(repository.findIncompleteRecordsByRecordKey("a").map { it.id }).containsExactly("a", "b", "c")
    }

    @Test
    fun `per key read excludes completed and failed records and retains future retries`() {
        create("n1", "a", now.minusSeconds(2))
        create("n2", "a", now.minusSeconds(1), retry = now.plusSeconds(3600))
        create("c1", "a", now.minusSeconds(3), status = OutboxRecordStatus.COMPLETED)
        create("f1", "a", now.minusSeconds(4), status = OutboxRecordStatus.FAILED)

        assertThat(repository.findIncompleteRecordsByRecordKey("a").map { it.id }).containsExactly("n1", "n2")
    }

    @Test
    fun `strict selection is blocked by an earlier equal time record while non strict selection is not`() {
        create("a1", "a", now.minusSeconds(5), retry = now.plusSeconds(3600))
        create("a2", "a", now.minusSeconds(5))

        assertThat(repository.findRecordKeysInPartitions(setOf(1), OutboxRecordStatus.NEW, 10, true)).isEmpty()
        assertThat(repository.findRecordKeysInPartitions(setOf(1), OutboxRecordStatus.NEW, 10, false))
            .containsExactly("a")
        assertThat(repository.findOldestDueNextRetryAtByPartitions(setOf(1), OutboxRecordStatus.NEW, now, true))
            .isNull()
    }

    @Test
    fun `key and age reads return nothing without records`() {
        listOf(true, false).forEach { filter ->
            assertThat(repository.findRecordKeysInPartitions(setOf(1), OutboxRecordStatus.NEW, 10, filter)).isEmpty()
            assertThat(repository.findOldestDueNextRetryAtByPartitions(setOf(1), OutboxRecordStatus.NEW, now, filter))
                .isNull()
        }
        assertThat(repository.findOldestCreatedAtByPartitions(setOf(1), OutboxRecordStatus.NEW)).isNull()
        assertThat(repository.countByStatuses(setOf(OutboxRecordStatus.NEW)))
            .containsExactlyEntriesOf(mapOf(OutboxRecordStatus.NEW to 0L))
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
        create("d1", "d", now.minusSeconds(40), status = OutboxRecordStatus.COMPLETED)
        create("d2", "d", now.minusSeconds(8), retry = now.minusSeconds(8))
        create("b1", "b", now.minusSeconds(20), retry = now.minusSeconds(20), status = OutboxRecordStatus.FAILED)
        create("b2", "b", now.minusSeconds(5), retry = now.minusSeconds(50))
        create("c1", "c", now.minusSeconds(30), retry = now.plusSeconds(3600))
        create("c2", "c", now.minusSeconds(1), retry = now.minusSeconds(1))
    }

    private fun create(
        id: String,
        key: String,
        created: Instant = now,
        retry: Instant = now,
        status: OutboxRecordStatus = OutboxRecordStatus.NEW,
        completed: Instant = now,
    ): OutboxRecord<String> =
        repository.create(
            OutboxRecord.restore(
                id = id,
                recordKey = key,
                payload = "payload-$id",
                context = mapOf("trace" to "original"),
                partition = 1,
                createdAt = created,
                status = status,
                completedAt = if (status == OutboxRecordStatus.COMPLETED) completed else null,
                failureCount = 0,
                failureException = null,
                failureReason = null,
                nextRetryAt = retry,
                handlerId = "handler",
            ),
        )

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
