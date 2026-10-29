package io.namastack.outbox

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException

@Testcontainers
class PostgresOutboxCompletionBatcherTest {
    private val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val source by lazy { DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password) }
    private val jdbc by lazy { JdbcClient.create(source) }
    private val manager by lazy { DataSourceTransactionManager(source) }
    private val transactions by lazy { TransactionTemplate(manager) }
    private val serializer = JacksonOutboxPayloadSerializer(JsonMapper.builder().build())

    @BeforeEach
    fun prepare() {
        ResourceDatabasePopulator(ClassPathResource("schema/postgres/outbox-tables.sql")).execute(source)
        JdbcTemplate(source).execute("TRUNCATE outbox_record")
    }

    private fun batcher(
        size: Int = 20,
        wait: Duration = Duration.ofNanos(250000),
    ) = PostgresOutboxCompletionBatcher(jdbc, transactions, "outbox_record", size, wait)

    private fun repository(writer: PostgresOutboxCompletionBatcher) =
        JdbcOutboxPersistenceFactory
            .create(
                source,
                manager,
                serializer,
                clock,
                completionBatcher = writer,
            ).recordRepository

    private fun create(
        repository: OutboxRecordRepository,
        key: String,
    ) = repository.create(
        OutboxRecord
            .Builder<String>()
            .key(key)
            .payload("original")
            .handlerId("handler")
            .build(clock),
    )

    @Test
    fun `bulk pages retain exact key boundaries and the ordered prefix`() {
        batcher().use { writer ->
            val repository = repository(writer)
            val first = List(3) { create(repository, "first") }.sortedBy { it.id }
            val second = create(repository, "second")
            val pages = repository.findIncompleteRecordPages(setOf("first", "second", "missing"), 2)
            assertThat(pages.keys).containsExactlyInAnyOrder("first", "second", "missing")
            assertThat(pages.getValue("first").map { it.id }).containsExactlyElementsOf(first.take(2).map { it.id })
            assertThat(pages.getValue("second").map { it.id }).containsExactly(second.id)
            assertThat(pages.getValue("missing")).isEmpty()
            assertThat(pages.getValue("first")[0]).isSameAs(pages.getValue("first")[0])
            assertThat(repository.findIncompleteRecordPages(emptySet())).isEmpty()
            assertThat(repository.findIncompleteRecordPages(setOf("first")).getValue("first")).hasSize(3)
        }
    }

    @Test
    fun `bulk pages expose retry metadata before decoding and isolate unavailable payloads`() {
        batcher().use { writer ->
            val repository = repository(writer)
            val bad = create(repository, "bad")
            val good = create(repository, "good")
            val future = clock.instant().plusSeconds(60)
            jdbc
                .sql("UPDATE outbox_record SET record_type='missing.Payload', next_retry_at=:retry WHERE id=:id")
                .param("retry", java.sql.Timestamp.from(future))
                .param("id", bad.id)
                .update()
            val pages = repository.findIncompleteRecordPages(setOf("bad", "good"))
            val unavailable = pages.getValue("bad")
            assertThat(unavailable).hasSize(1)
            assertThat(unavailable.nextRetryAt(0)).isEqualTo(future)
            assertThat(unavailable.recordId(0)).isEqualTo(bad.id)
            assertThat(pages.getValue("good")[0].id).isEqualTo(good.id)
            assertThatThrownBy { unavailable[0] }.isInstanceOf(OutboxPayloadTypeNotFoundException::class.java)
        }
    }

    @Test
    fun `acknowledgements wait for the shared transaction to commit`() {
        batcher(2, Duration.ofSeconds(1)).use { writer ->
            val repository = repository(writer)
            val records = listOf(create(repository, "first"), create(repository, "second")).map(::completed)
            val workers = Executors.newFixedThreadPool(2)
            source.connection.use { blocker ->
                blocker.autoCommit = false
                blocker.createStatement().use { it.execute("LOCK TABLE outbox_record IN ACCESS EXCLUSIVE MODE") }
                val futures = records.map { record -> workers.submit<Boolean> { repository.updateCompletion(record) } }
                try {
                    assertThatThrownBy {
                        futures.first().get(
                            100,
                            TimeUnit.MILLISECONDS,
                        )
                    }.isInstanceOf(TimeoutException::class.java)
                    blocker.rollback()
                    futures.forEach { assertThat(it.get(5, SECONDS)).isTrue() }
                    assertThat(
                        jdbc
                            .sql(
                                "SELECT COUNT(*) FROM outbox_record WHERE status='COMPLETED'",
                            ).query(Long::class.java)
                            .single(),
                    ).isEqualTo(2)
                } finally {
                    blocker.rollback()
                    workers.shutdownNow()
                }
            }
        }
    }

    @Test
    fun `a poisoned completion does not block unrelated keys`() {
        batcher(2, Duration.ofSeconds(1)).use { writer ->
            val repository = repository(writer)
            val good = completed(create(repository, "good"))
            val bad = completed(create(repository, "bad"), failures = 3)
            jdbc.sql("ALTER TABLE outbox_record ADD CONSTRAINT poison_failure_count CHECK (failure_count < 2)").update()
            val workers = Executors.newFixedThreadPool(2)
            try {
                val accepted = workers.submit<Boolean> { repository.updateCompletion(good) }
                val rejected = workers.submit<Boolean> { repository.updateCompletion(bad) }
                assertThat(accepted.get(5, SECONDS)).isTrue()
                assertThatThrownBy { rejected.get(5, SECONDS) }.hasCauseInstanceOf(RuntimeException::class.java)
                assertThat(repository.findCompletedRecords().map { it.id }).containsExactly(good.id)
                assertThat(repository.findIncompleteRecordsByRecordKey("bad").map { it.id }).containsExactly(bad.id)
            } finally {
                workers.shutdownNow()
                jdbc.sql("ALTER TABLE outbox_record DROP CONSTRAINT poison_failure_count").update()
            }
        }
    }

    @Test
    fun `an application transaction uses the original connection and rolls back completion`() {
        batcher().use { writer ->
            val repository = repository(writer)
            val record = create(repository, "rollback")
            transactions.executeWithoutResult { transaction ->
                assertThat(repository.updateCompletion(completed(record))).isTrue()
                transaction.setRollbackOnly()
            }
            assertThat(repository.findIncompleteRecordsByRecordKey("rollback").map { it.id }).containsExactly(record.id)
            assertThat(repository.findCompletedRecords()).isEmpty()
        }
    }

    @Test
    fun `missing records and batch tails finish and shutdown rejects later writes`() {
        val writer = batcher(32)
        val repository = repository(writer)
        val record = completed(create(repository, "tail"))
        try {
            assertThat(repository.updateCompletion(record)).isTrue()
            repository.deleteById(record.id)
            assertThat(repository.updateCompletion(record)).isFalse()
        } finally {
            writer.close()
        }
        assertThatThrownBy { repository.updateCompletion(record) }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `close flushes pending writes instead of waiting for the coalescing timeout`() {
        val writer = batcher(32, Duration.ofSeconds(10))
        val repository = repository(writer)
        val record = completed(create(repository, "shutdown"))
        val worker = Executors.newSingleThreadExecutor()
        try {
            val future = worker.submit<Boolean> { repository.updateCompletion(record) }
            assertThatThrownBy { future.get(100, TimeUnit.MILLISECONDS) }.isInstanceOf(TimeoutException::class.java)
            writer.close()
            assertThat(future.get(2, SECONDS)).isTrue()
            assertThat(repository.findCompletedRecords().map { it.id }).containsExactly(record.id)
        } finally {
            writer.close()
            worker.shutdownNow()
        }
    }

    @Test
    fun `programmatic batching rejects a mismatched table namespace`() {
        JdbcOutboxPersistenceFactory.completionBatcher(source, manager).use { writer ->
            assertThatThrownBy {
                JdbcOutboxPersistenceFactory.create(
                    source,
                    manager,
                    serializer,
                    clock,
                    tableNameResolver = DefaultJdbcTableNameResolver(tablePrefix = "other_"),
                    completionBatcher = writer,
                )
            }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    private fun completed(
        record: OutboxRecord<*>,
        failures: Int = record.failureCount,
    ) = OutboxRecord.restore(
        id = record.id,
        recordKey = record.key,
        payload = record.payload,
        context = record.context,
        partition = record.partition,
        createdAt = record.createdAt,
        status = OutboxRecordStatus.COMPLETED,
        completedAt = clock.instant(),
        failureCount = failures,
        failureException = record.failureException,
        failureReason = record.failureReason,
        nextRetryAt = record.nextRetryAt,
        handlerId = record.handlerId,
    )

    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
