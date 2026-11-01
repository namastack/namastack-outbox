package io.namastack.outbox

import io.namastack.outbox.config.JdbcOutboxAutoConfiguration
import io.namastack.outbox.config.JdbcOutboxSchemaAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
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
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS

@JdbcTest(
    properties = [
        "namastack.outbox.jdbc.completion-batching.enabled=true",
        "namastack.outbox.jdbc.completion-batching.max-batch-size=8",
        "namastack.outbox.jdbc.completion-batching.max-wait=1ms",
        "namastack.outbox.jdbc.completion-batching.writer-count=2",
    ],
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(
    JdbcClientAutoConfiguration::class,
    JdbcOutboxAutoConfiguration::class,
    JdbcOutboxSchemaAutoConfiguration::class,
    OutboxJacksonAutoConfiguration::class,
)
@ContextConfiguration(classes = [JdbcOutboxRecordRepositoryTest.TestApplication::class])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
abstract class AbstractJdbcCompletionBatchingTest {
    @Autowired private lateinit var repository: OutboxRecordRepository

    @Autowired private lateinit var jdbc: JdbcClient

    @Autowired private lateinit var transactions: TransactionTemplate
    private val clock = Clock.systemUTC()

    @BeforeEach
    fun clearRecords() {
        jdbc.sql("DELETE FROM outbox_record").update()
    }

    @Test
    fun `parallel JDBC completions preserve stored payload and context`() {
        val records = List(32) { create("key-$it") }
        val workers = Executors.newFixedThreadPool(8)
        try {
            val updates =
                records.map { record ->
                    workers.submit<Boolean> { repository.updateProcessingState(completed(record)) }
                }
            updates.forEach { assertThat(it.get(10, SECONDS)).isTrue() }
            val stored = repository.findCompletedRecords()
            assertThat(stored).hasSize(records.size)
            stored.forEach { record ->
                assertThat(record.payload).isEqualTo("original")
                assertThat(record.context).containsExactlyEntriesOf(mapOf("trace" to "original"))
                assertThat(record.completedAt).isNotNull()
                assertThat(record.failureCount).isEqualTo(3)
                assertThat(record.failureReason).isNull()
            }
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun `next record of each key observes the committed predecessor`() {
        val keys = List(4) { key -> List(8) { create("key-$key") } }
        val workers = Executors.newFixedThreadPool(keys.size)
        try {
            val updates =
                keys.map { records ->
                    workers.submit {
                        records.forEachIndexed { index, record ->
                            if (index > 0) {
                                assertThat(
                                    jdbc
                                        .sql("SELECT status FROM outbox_record WHERE id=:id")
                                        .param("id", records[index - 1].id)
                                        .query(String::class.java)
                                        .single(),
                                ).isEqualTo("COMPLETED")
                            }
                            assertThat(repository.updateProcessingState(completed(record))).isTrue()
                        }
                    }
                }
            updates.forEach { it.get(10, SECONDS) }
            assertThat(repository.findCompletedRecords()).hasSize(32)
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun `application transaction keeps ownership of completion and rollback`() {
        val record = create("rollback")
        transactions.executeWithoutResult { transaction ->
            assertThat(repository.updateProcessingState(completed(record))).isTrue()
            assertThat(repository.findCompletedRecords()).hasSize(1)
            transaction.setRollbackOnly()
        }
        assertThat(repository.findCompletedRecords()).isEmpty()
        assertThat(repository.findIncompleteRecordsByRecordKey("rollback").map { it.id }).containsExactly(record.id)
    }

    @Test
    fun `partial batches return exact false result for missing records`() {
        val record = create("missing")
        repository.deleteById(record.id)
        assertThat(repository.updateProcessingState(completed(record))).isFalse()
        val tail = create("tail")
        assertThat(repository.updateProcessingState(completed(tail))).isTrue()
        assertThat(repository.findCompletedRecords().map { it.id }).containsExactly(tail.id)
    }

    private fun create(key: String) =
        repository.create(
            OutboxRecord
                .Builder<String>()
                .key(key)
                .payload("original")
                .context(mapOf("trace" to "original"))
                .handlerId("handler")
                .build(clock),
        )

    private fun completed(record: OutboxRecord<*>) =
        OutboxRecord.restore(
            id = record.id,
            recordKey = record.key,
            payload = "must not be written",
            context = emptyMap(),
            createdAt = record.createdAt,
            status = OutboxRecordStatus.COMPLETED,
            completedAt = clock.instant(),
            failureCount = 3,
            failureException = null,
            failureReason = null,
            partition = record.partition,
            nextRetryAt = record.nextRetryAt,
            handlerId = record.handlerId,
        )
}
