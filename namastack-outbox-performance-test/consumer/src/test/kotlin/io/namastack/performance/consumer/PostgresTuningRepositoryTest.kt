package io.namastack.performance.consumer

import io.mockk.every
import io.mockk.mockk
import io.namastack.outbox.JdbcOutboxRecordEntityMapper
import io.namastack.outbox.OutboxPayloadSerializer
import io.namastack.outbox.OutboxPayloadTypeNotFoundException
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxRecordStatus
import io.namastack.outbox.OutboxRecordStatusRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException

@Testcontainers
class PostgresTuningRepositoryTest {
    private val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val source by lazy { DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password) }
    private val jdbc by lazy { JdbcClient.create(source) }
    private val transactions by lazy { TransactionTemplate(DataSourceTransactionManager(source)) }
    private val serializer = mockk<OutboxPayloadSerializer>()
    private val delegate = mockk<OutboxRecordRepository>(relaxed = true)
    private val status = mockk<OutboxRecordStatusRepository>(relaxed = true)

    @BeforeEach
    fun prepare() {
        ResourceDatabasePopulator(ClassPathResource("schema/postgres/outbox-tables.sql")).execute(source)
        JdbcTemplate(source).execute("TRUNCATE outbox_record")
        every { serializer.deserialize(any(), String::class.java) } answers { firstArg<String>() }
        every { delegate.withRecordKeyProcessing<Unit>(any(), any()) } answers { secondArg<() -> Unit>().invoke() }
    }

    private fun repository(settings: TuningSettings) =
        PostgresTuningRepository(
            delegate,
            status,
            jdbc,
            JdbcOutboxRecordEntityMapper(serializer),
            transactions,
            clock,
            settings,
        )

    private fun insert(
        id: String,
        key: String,
        seconds: Long = 0,
        status: String = "NEW",
        type: String = "java.lang.String",
        future: Boolean = false,
    ) {
        jdbc
            .sql(
                """
          INSERT INTO outbox_record(id,status,record_key,record_type,payload,context,created_at,completed_at,
          failure_count,failure_reason,next_retry_at,partition_no,handler_id)
          VALUES(:id,:status,:key,:type,'payload',NULL,:created,NULL,0,NULL,:retry,1,'handler')
        """,
            ).param("id", id)
            .param("status", status)
            .param("key", key)
            .param("type", type)
            .param("created", Timestamp.from(clock.instant().plusSeconds(seconds)))
            .param("retry", Timestamp.from(if (future) clock.instant().plusSeconds(60) else clock.instant()))
            .update()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `SQL limit and inline status preserve failed and future predecessor barriers`(headLimit: Boolean) {
        insert("a1", "blocked", status = "FAILED")
        insert("a2", "blocked", seconds = 1)
        insert("b1", "future", future = true)
        insert("b2", "future", seconds = 1)
        insert("c1", "ready", seconds = 2)
        insert("d1", "other", seconds = 3)
        for (tuple in listOf(false,true)) {
        for (inline in listOf(false, true)) {
            val repo = repository(TuningSettings(sqlLimit = true, inlineStatus = inline, headLimitKeys = headLimit, tuplePredecessor = tuple))
            assertThat(
                repo.findRecordKeysInPartitions(setOf(1), OutboxRecordStatus.NEW, 1, true),
            ).containsExactly("ready")
            assertThat(
                repo.findRecordKeysInPartitions(setOf(1), OutboxRecordStatus.NEW, 10, true),
            ).containsExactly("ready", "other")
        }
        }
    }

    @Test
    fun `limited head selection keeps tied ordering and does not emit duplicate keys`() {
        insert("a2", "tied")
        insert("a1", "tied")
        insert("b1", "other", seconds = 1)
        val repo=repository(TuningSettings(headLimitKeys=true,tuplePredecessor=true))
        assertThat(repo.findRecordKeysInPartitions(setOf(1),OutboxRecordStatus.NEW,1,true)).containsExactly("tied")
        assertThat(repo.findRecordKeysInPartitions(setOf(1),OutboxRecordStatus.NEW,10,true))
            .containsExactly("tied","other")
        // Preserve uniqueness even with inconsistent data outside the normal record invariants.
        jdbc.sql("UPDATE outbox_record SET completed_at=:now WHERE record_key='tied'")
            .param("now",Timestamp.from(clock.instant())).update()
        assertThat(repo.findRecordKeysInPartitions(setOf(1),OutboxRecordStatus.NEW,2,true)).containsExactly("tied")
        assertThat(repo.findRecordKeysInPartitions(setOf(1),OutboxRecordStatus.NEW,10,true))
            .containsExactly("tied","other")
        assertThat(repo.findRecordKeysInPartitions(emptySet(),OutboxRecordStatus.NEW,10,true)).isEmpty()
    }

    @Test
    fun `head selection retains grouped behavior for non-strict processing and other statuses`() {
        insert("a1","failed",status="FAILED")
        insert("a2","failed",seconds=1)
        insert("b1","repeated",seconds=2)
        insert("b2","repeated",seconds=3)
        val repo=repository(TuningSettings(headLimitKeys=true,tuplePredecessor=true))
        assertThat(repo.findRecordKeysInPartitions(setOf(1),OutboxRecordStatus.NEW,10,false))
            .containsExactly("failed","repeated")
        assertThat(repo.findRecordKeysInPartitions(setOf(1),OutboxRecordStatus.FAILED,10,true)).containsExactly("failed")
    }

    @Test
    fun `bulk loading keeps tied ID order and defers a successor deserialization error`() {
        insert("a2", "key", type = "missing.Payload")
        insert("a1", "key")
        val repo = repository(TuningSettings(sqlLimit = true, bulkFetchSize = 50, lazyDeserialization = true))
        assertThat(repo.findRecordKeysInPartitions(setOf(1), OutboxRecordStatus.NEW, 100, true)).containsExactly("key")
        val records = repo.findIncompleteRecordsByRecordKey("key")
        assertThat(records[0].id).isEqualTo("a1")
        assertThatThrownBy { records[1] }.isInstanceOf(OutboxPayloadTypeNotFoundException::class.java)
    }

    @Test
    fun `future retry is not decoded and blocks its successors in ordered mode`() {
        insert("a1", "key")
        insert("a2", "key", seconds = 1, type = "missing.Payload", future = true)
        insert("a3", "key", seconds = 2)
        val repo = repository(TuningSettings(bulkFetchSize = 50, lazyDeserialization = true))
        repo.findRecordKeysInPartitions(setOf(1), OutboxRecordStatus.NEW, 100, true)
        assertThat(repo.findIncompleteRecordsByRecordKey("key").map { it.id }).containsExactly("a1")
    }

    @Test
    fun `per key row cap permits the next head on the next poll`() {
        insert("a2", "key")
        insert("a1", "key")
        val repo =
            repository(
                TuningSettings(
                    bulkFetchSize = 50,
                    maxRecordsPerKey = 1,
                    lazyDeserialization = true,
                    autocommitCompletion = true,
                ),
            )
        repo.findRecordKeysInPartitions(setOf(1), OutboxRecordStatus.NEW, 100, true)
        val first = repo.findIncompleteRecordsByRecordKey("key").single()
        assertThat(first.id).isEqualTo("a1")
        assertThat(repo.updateCompletion(completed(first))).isTrue()
        repo.findRecordKeysInPartitions(setOf(1), OutboxRecordStatus.NEW, 100, true)
        assertThat(repo.findIncompleteRecordsByRecordKey("key").map { it.id }).containsExactly("a2")
    }

    @Test
    fun `per key transaction rolls back a completed prefix after a processing error`() {
        insert("a1", "key")
        val repo =
            repository(
                TuningSettings(lazyDeserialization = true, autocommitCompletion = true, transactionPerKey = true),
            )
        val first = repo.findIncompleteRecordsByRecordKey("key").first()
        assertThatThrownBy {
            repo.withRecordKeyProcessing("key") {
                check(repo.updateCompletion(completed(first)))
                error("successor failed")
            }
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(
            jdbc.sql("SELECT status FROM outbox_record WHERE id='a1'").query(String::class.java).single(),
        ).isEqualTo("NEW")
        repo.withRecordKeyProcessing("key") { check(repo.updateCompletion(completed(first))) }
        assertThat(
            jdbc.sql("SELECT status FROM outbox_record WHERE id='a1'").query(String::class.java).single(),
        ).isEqualTo("COMPLETED")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `batched acknowledgements wait until the shared database transaction commits`(arrays: Boolean) {
        insert("a1", "first")
        insert("b1", "second")
        val repo = repository(TuningSettings(lazyDeserialization = true))
        val records = listOf("first", "second").map { completed(repo.findIncompleteRecordsByRecordKey(it).single()) }
        val workers = Executors.newVirtualThreadPerTaskExecutor()
        source.connection.use { blocking ->
            blocking.autoCommit = false
            blocking.createStatement().use { it.execute("LOCK TABLE outbox_record IN ACCESS EXCLUSIVE MODE") }
            CompletionBatcher(jdbc, transactions, 2, 1000000, useArrays = arrays).use { batcher ->
                val entered = CountDownLatch(2)
                val futures =
                    records.map { record ->
                        workers.submit<Boolean> {
                            entered.countDown()
                            batcher.complete(record)
                        }
                    }
                try {
                    check(entered.await(2, SECONDS))
                    assertThatThrownBy { futures.first().get(100, java.util.concurrent.TimeUnit.MILLISECONDS) }
                        .isInstanceOf(TimeoutException::class.java)
                    blocking.rollback()
                    futures.forEach { assertThat(it.get(5, SECONDS)).isTrue() }
                    assertThat(
                        jdbc
                            .sql("SELECT COUNT(*) FROM outbox_record WHERE status='COMPLETED'")
                            .query(Long::class.java)
                            .single(),
                    ).isEqualTo(2)
                } finally {
                    blocking.rollback()
                    workers.shutdownNow()
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `one invalid completion rolls back its whole write batch and rejects every acknowledgement`(arrays: Boolean) {
        insert("a1", "first")
        insert("b1", "second")
        jdbc.sql("ALTER TABLE outbox_record ADD CONSTRAINT test_batch_failures CHECK (failure_count < 2)").update()
        val repo = repository(TuningSettings(lazyDeserialization = true))
        val good = completed(repo.findIncompleteRecordsByRecordKey("first").single())
        val invalid = completed(repo.findIncompleteRecordsByRecordKey("second").single(), failures = 3)
        val workers = Executors.newVirtualThreadPerTaskExecutor()
        try {
            CompletionBatcher(jdbc, transactions, 2, 1000000, useArrays = arrays).use { batcher ->
                val gate = CountDownLatch(1)
                val futures =
                    listOf(good, invalid).map { record ->
                        workers.submit<Boolean> {
                            gate.await()
                            batcher.complete(record)
                        }
                    }
                gate.countDown()
                futures.forEach { future ->
                    assertThatThrownBy { future.get(5, SECONDS) }.hasCauseInstanceOf(RuntimeException::class.java)
                }
                assertThat(
                    jdbc.sql("SELECT COUNT(*) FROM outbox_record WHERE status='NEW'").query(Long::class.java).single(),
                ).isEqualTo(2)
            }
        } finally {
            workers.shutdownNow()
            jdbc.sql("ALTER TABLE outbox_record DROP CONSTRAINT test_batch_failures").update()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `batch tail and missing rows finish without waiting for a full batch`(arrays: Boolean) {
        insert("a1", "key")
        val record =
            completed(
                repository(TuningSettings(lazyDeserialization = true)).findIncompleteRecordsByRecordKey("key").single(),
            )
        val batcher = CompletionBatcher(jdbc, transactions, 32, 250, useArrays = arrays)
        try {
            assertThat(batcher.complete(record)).isTrue()
            jdbc.sql("DELETE FROM outbox_record").update()
            assertThat(batcher.complete(record)).isFalse()
        } finally {
            batcher.close()
        }
        assertThatThrownBy { batcher.complete(record) }.isInstanceOf(IllegalStateException::class.java)
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

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `batch parameters preserve escaped text and timestamp precision`(arrays: Boolean) {
        val id = "escaped-\",{ä}\\value"
        val reason = "failure, \"quoted\" {NULL} \\ slash\nnext line"
        val at = Instant.parse("2026-01-01T00:00:00.123456Z")
        insert(id, "key")
        val record =
            OutboxRecord.restore(
                id = id,
                recordKey = "key",
                payload = "payload",
                context = emptyMap(),
                partition = 1,
                createdAt = clock.instant(),
                status = OutboxRecordStatus.COMPLETED,
                completedAt = at,
                failureCount = 1,
                failureReason = reason,
                nextRetryAt = at.plusSeconds(60),
                handlerId = "handler",
                failureException = null,
            )
        CompletionBatcher(jdbc, transactions, 32, 250, useArrays = arrays).use { batcher ->
            assertThat(batcher.complete(record)).isTrue()
            val row =
                jdbc
                    .sql("SELECT completed_at,next_retry_at,failure_reason FROM outbox_record WHERE id=:id")
                    .param("id", id)
                    .query {
                        rs,
                        _,
                        ->
                        Triple(rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant(), rs.getString(3))
                    }.single()
            assertThat(row).isEqualTo(Triple(at, at.plusSeconds(60), reason))
        }
    }

    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }
}
