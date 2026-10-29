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
        repository =
            JdbcOutboxPersistenceFactory
                .create(source, transactions.transactionManager!!, serializer, clock)
                .recordRepository
    }

    @Test
    fun `pages retain exact key boundaries and creation time then id ordering`() {
        create("a3", "a", now.plusSeconds(1))
        create("b1", "b")
        create("a2", "a")
        create("a1", "a")
        val pages = repository.findIncompleteRecordPages(setOf("b", "a", "missing"))

        assertThat(pages.keys).containsExactlyInAnyOrder("a", "b", "missing")
        assertThat(pages.getValue("a").map { it.id }).containsExactly("a1", "a2", "a3")
        assertThat(pages.getValue("b").map { it.id }).containsExactly("b1")
        assertThat(pages.getValue("missing")).isEmpty()
        assertThat(pages.getValue("a").map { it.id })
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

        assertThat(page.map { it.id }).containsExactly("a1", "a2", "a3")
        assertThat(page.nextRetryAt(1)).isEqualTo(now.plusSeconds(3600))
    }

    @Test
    fun `metadata access does not decode payload or context and decoded objects retain mutations`() {
        create("a1", "a")
        serializer.reset()

        val page = repository.findIncompleteRecordPages(setOf("a")).getValue("a")

        assertThat(page).hasSize(1)
        assertThat(page.recordId(0)).isEqualTo("a1")
        assertThat(page.nextRetryAt(0)).isEqualTo(now)
        assertThat(page.isRetryDue(0, Clock.fixed(now.minusNanos(1), UTC))).isFalse()
        assertThat(page.isRetryDue(0, clock)).isTrue()
        assertThat(page.isRetryDue(0, Clock.fixed(now.plusNanos(1), UTC))).isTrue()
        assertThat(serializer.deserializations).isZero()
        assertThat(serializer.serializations).isZero()

        val record = page[0]
        assertThat(record.payload).isEqualTo("payload-a1")
        assertThat(record.context).containsEntry("trace", "original")
        assertThat(serializer.deserializations).isEqualTo(2)
        (record.context as MutableMap<String, String>)["trace"] = "mutated"
        assertThat(page[0]).isSameAs(record)
        assertThat(page[0].context).containsEntry("trace", "mutated")
        assertThat(serializer.deserializations).isEqualTo(2)
    }

    @Test
    fun `unavailable future payload types do not prevent metadata access or reading another key`() {
        create("bad", "a", retry = now.plusSeconds(60))
        create("good", "b")
        jdbc.sql("UPDATE outbox_record SET record_type = 'missing.Payload' WHERE id = 'bad'").update()
        serializer.reset()

        val pages = repository.findIncompleteRecordPages(setOf("a", "b"))

        assertThat(pages.getValue("a").recordId(0)).isEqualTo("bad")
        assertThat(pages.getValue("a").nextRetryAt(0)).isEqualTo(now.plusSeconds(60))
        assertThat(pages.getValue("a").isRetryDue(0, clock)).isFalse()
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
        assertThat(page.recordId(1)).isEqualTo("a2")
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

        assertThat(oldPage.map { it.id }).containsExactly("a1")
        assertThat(repository.findIncompleteRecordPages(setOf("a")).getValue("a").map { it.id })
            .containsExactly("a2")
    }

    @Test
    fun `empty requests return no pages and the portable key bound is enforced`() {
        assertThat(repository.findIncompleteRecordPages(emptySet())).isEmpty()
        assertThat(repository.findIncompleteRecordPages((1..1000).map { "missing-$it" }.toSet())).hasSize(1000)
        assertThatThrownBy { repository.findIncompleteRecordPages((1..1001).map { "missing-$it" }.toSet()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun create(
        id: String,
        key: String,
        created: Instant = now,
        retry: Instant = now,
        status: OutboxRecordStatus = OutboxRecordStatus.NEW,
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
                completedAt = if (status == OutboxRecordStatus.COMPLETED) now else null,
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
}
