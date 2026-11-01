package io.namastack.outbox

import io.mockk.every
import io.mockk.spyk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.sql.Statement
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class JdbcOutboxCompletionBatcherTest {
    private lateinit var database: EmbeddedDatabase
    private lateinit var jdbc: JdbcTemplate
    private lateinit var transactions: TransactionTemplate
    private val clock = Clock.systemUTC()
    private val commits = AtomicInteger()

    @Volatile private var beforeCommit: () -> Unit = {}

    @Volatile private var afterCommit: () -> Unit = {}

    @BeforeEach
    fun setUp() {
        database =
            EmbeddedDatabaseBuilder()
                .generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2)
                .addScript("schema/h2/outbox-tables.sql")
                .build()
        jdbc = JdbcTemplate(database)
        transactions =
            TransactionTemplate(
                object : DataSourceTransactionManager(database) {
                    override fun doCommit(status: DefaultTransactionStatus) {
                        beforeCommit()
                        super.doCommit(status)
                        commits.incrementAndGet()
                        afterCommit()
                    }
                },
            )
    }

    @AfterEach
    fun tearDown() {
        database.shutdown()
    }

    private fun writer(
        size: Int = 256,
        wait: Duration = Duration.ofNanos(25_000),
        operations: JdbcOperations = jdbc,
        writers: Int = 1,
    ) = JdbcOutboxCompletionBatcher(operations, transactions, "outbox_record", size, wait, 4096, writers)

    private fun repository(writer: JdbcOutboxCompletionBatcher) =
        JdbcOutboxRecordRepository(
            JdbcClient.create(jdbc),
            transactions,
            JdbcOutboxRecordEntityMapper(JacksonOutboxPayloadSerializer(JsonMapper.builder().build())),
            clock,
            DefaultJdbcTableNameResolver(),
            databaseTypeResolver = { io.namastack.outbox.config.JdbcDatabaseType.H2 },
            completionBatcher = writer,
        )

    private fun create(
        repository: OutboxRecordRepository,
        key: String = "key",
    ) = repository.create(
        OutboxRecord
            .Builder<String>()
            .key(key)
            .payload("original")
            .handlerId("handler")
            .build(clock),
    )

    private fun completed(
        record: OutboxRecord<*>,
        failures: Int = record.failureCount,
    ) = OutboxRecord.restore(
        id = record.id,
        recordKey = record.key,
        payload = record.payload,
        context = record.context,
        createdAt = record.createdAt,
        status = OutboxRecordStatus.COMPLETED,
        completedAt = clock.instant(),
        failureCount = failures,
        failureException = null,
        failureReason = null,
        partition = record.partition,
        nextRetryAt = record.nextRetryAt,
        handlerId = record.handlerId,
    )

    private fun completedCount(): Long =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox_record WHERE status='COMPLETED'",
            Long::class.java,
        )!!

    @Test
    fun `a second writer commits independent keys while the first commit is blocked`() {
        writer(1, writers = 2).use { writer ->
            val repository = repository(writer)
            val first = completed(create(repository, "first"))
            val second = completed(create(repository, "second"))
            val enteredCommit = CountDownLatch(1)
            val releaseCommit = CountDownLatch(1)
            val commitAttempts = AtomicInteger()
            beforeCommit = {
                if (commitAttempts.getAndIncrement() == 0) {
                    enteredCommit.countDown()
                    check(releaseCommit.await(5, SECONDS))
                }
            }
            val worker = Executors.newSingleThreadExecutor()
            try {
                val blocked = worker.submit<Boolean> { repository.updateProcessingState(first) }
                assertThat(enteredCommit.await(5, SECONDS)).isTrue()
                assertThat(repository.updateProcessingState(second)).isTrue()
                assertThat(blocked.isDone).isFalse()
                assertThat(repository.findCompletedRecords().map { it.id }).containsExactly(second.id)
                releaseCommit.countDown()
                assertThat(blocked.get(5, SECONDS)).isTrue()
                assertThat(completedCount()).isEqualTo(2)
            } finally {
                releaseCommit.countDown()
                worker.shutdownNow()
            }
        }
    }

    @Test
    fun `independent completions share a transaction and wait for its commit`() {
        writer(2, Duration.ofSeconds(1)).use { writer ->
            val repository = repository(writer)
            val records = listOf(completed(create(repository, "first")), completed(create(repository, "second")))
            commits.set(0)
            val enteredCommit = CountDownLatch(1)
            val releaseCommit = CountDownLatch(1)
            beforeCommit = {
                enteredCommit.countDown()
                check(releaseCommit.await(5, SECONDS))
            }
            val workers = Executors.newFixedThreadPool(2)
            try {
                val results =
                    records.map { record ->
                        workers.submit<Boolean> { repository.updateProcessingState(record) }
                    }
                assertThat(enteredCommit.await(5, SECONDS)).isTrue()
                assertThat(results).allMatch { !it.isDone }
                assertThat(completedCount()).isZero()
                releaseCommit.countDown()
                results.forEach { assertThat(it.get(5, SECONDS)).isTrue() }
                assertThat(commits.get()).isEqualTo(1)
                assertThat(completedCount()).isEqualTo(2)
            } finally {
                releaseCommit.countDown()
                workers.shutdownNow()
            }
        }
    }

    @Test
    fun `application transaction completion rolls back with its business transaction`() {
        writer().use { writer ->
            val repository = repository(writer)
            val record = create(repository)
            transactions.executeWithoutResult { transaction ->
                assertThat(repository.updateProcessingState(completed(record))).isTrue()
                assertThat(completedCount()).isEqualTo(1)
                transaction.setRollbackOnly()
            }
            assertThat(repository.findIncompleteRecordsByRecordKey(record.key).map { it.id }).containsExactly(record.id)
            assertThat(completedCount()).isZero()
        }
    }

    @Test
    fun `rolled back constraint failure is isolated to the poisoned key`() {
        writer(2, Duration.ofSeconds(1)).use { writer ->
            val repository = repository(writer)
            val good = completed(create(repository, "good"))
            val bad = completed(create(repository, "bad"), failures = 3)
            jdbc.execute("ALTER TABLE outbox_record ADD CONSTRAINT valid_failures CHECK (failure_count < 2)")
            val workers = Executors.newFixedThreadPool(2)
            try {
                val accepted = workers.submit<Boolean> { repository.updateProcessingState(good) }
                val rejected = workers.submit<Boolean> { repository.updateProcessingState(bad) }
                assertThat(accepted.get(5, SECONDS)).isTrue()
                assertThatThrownBy {
                    rejected.get(
                        5,
                        SECONDS,
                    )
                }.hasCauseInstanceOf(DataIntegrityViolationException::class.java)
                assertThat(repository.findCompletedRecords().map { it.id }).containsExactly(good.id)
                assertThat(repository.findIncompleteRecordsByRecordKey("bad").map { it.id }).containsExactly(bad.id)
            } finally {
                workers.shutdownNow()
            }
        }
    }

    @Test
    fun `unknown batch counts roll back before exact updates and disable further batching`() {
        val operations = spyk(jdbc)
        every { operations.batchUpdate(any<String>(), any<BatchPreparedStatementSetter>()) } answers {
            jdbc.batchUpdate(firstArg<String>(), secondArg<BatchPreparedStatementSetter>())
            IntArray(secondArg<BatchPreparedStatementSetter>().batchSize) { Statement.SUCCESS_NO_INFO }
        }
        writer(2, Duration.ofMillis(10), operations).use { writer ->
            val repository = repository(writer)
            val good = completed(create(repository))
            assertThat(repository.updateProcessingState(good)).isTrue()
            repository.deleteById(good.id)
            assertThat(repository.updateProcessingState(good)).isFalse()
            assertThat(repository.updateProcessingState(completed(create(repository, "next")))).isTrue()
            verify(exactly = 1) { operations.batchUpdate(any<String>(), any<BatchPreparedStatementSetter>()) }
        }
    }

    @Test
    fun `confirmed deadlock rollbacks retry metadata without acknowledging rolled back writes`() {
        val operations = spyk(jdbc)
        every { operations.batchUpdate(any<String>(), any<BatchPreparedStatementSetter>()) } answers {
            jdbc.batchUpdate(firstArg<String>(), secondArg<BatchPreparedStatementSetter>())
            throw CannotAcquireLockException("simulated deadlock after writes")
        }
        val isolatedAttempts = AtomicInteger()
        every { operations.update(any<String>(), any<PreparedStatementSetter>()) } answers {
            val updated = jdbc.update(firstArg<String>(), secondArg<PreparedStatementSetter>())
            if (isolatedAttempts.getAndIncrement() == 0) throw CannotAcquireLockException("isolated deadlock")
            updated
        }
        writer(1, operations = operations).use { writer ->
            val repository = repository(writer)
            val record = completed(create(repository))
            commits.set(0)
            assertThat(repository.updateProcessingState(record)).isTrue()
            assertThat(commits.get()).isEqualTo(1)
            assertThat(completedCount()).isEqualTo(1)
            assertThat(isolatedAttempts.get()).isEqualTo(2)
        }
    }

    @Test
    fun `commit failure is reported without blindly replaying an uncertain commit`() {
        val operations = spyk(jdbc)
        writer(1, operations = operations).use { writer ->
            val repository = repository(writer)
            val record = completed(create(repository))
            commits.set(0)
            afterCommit = { throw TransactionSystemException("commit acknowledgement lost") }
            assertThatThrownBy {
                repository.updateProcessingState(
                    record,
                )
            }.isInstanceOf(TransactionSystemException::class.java)
            assertThat(commits.get()).isEqualTo(1)
            assertThat(completedCount()).isEqualTo(1)
            verify(exactly = 1) { operations.batchUpdate(any<String>(), any<BatchPreparedStatementSetter>()) }
        }
    }

    @Test
    fun `interrupt does not let a worker advance before commit`() {
        writer(1).use { writer ->
            val repository = repository(writer)
            val record = completed(create(repository))
            val enteredCommit = CountDownLatch(1)
            val releaseCommit = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val interruptRetained =
                java.util.concurrent.atomic
                    .AtomicBoolean()
            beforeCommit = {
                enteredCommit.countDown()
                check(releaseCommit.await(5, SECONDS))
            }
            val worker =
                Thread {
                    repository.updateProcessingState(record)
                    interruptRetained.set(Thread.currentThread().isInterrupted)
                    finished.countDown()
                }
            try {
                worker.start()
                assertThat(enteredCommit.await(5, SECONDS)).isTrue()
                worker.interrupt()
                assertThat(finished.await(100, MILLISECONDS)).isFalse()
                assertThat(completedCount()).isZero()
                releaseCommit.countDown()
                assertThat(finished.await(5, SECONDS)).isTrue()
                assertThat(interruptRetained.get()).isTrue()
            } finally {
                releaseCommit.countDown()
                worker.join(5000)
            }
        }
    }

    @Test
    fun `close flushes a partial batch without waiting for the collection deadline`() {
        val writer = writer(256, Duration.ofSeconds(10))
        val repository = repository(writer)
        val record = completed(create(repository))
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Boolean> { repository.updateProcessingState(record) }
            assertThatThrownBy { result.get(100, MILLISECONDS) }.isInstanceOf(TimeoutException::class.java)
            writer.close()
            assertThat(result.get(1, SECONDS)).isTrue()
            assertThat(completedCount()).isEqualTo(1)
            assertThatThrownBy {
                repository.updateProcessingState(
                    record,
                )
            }.isInstanceOf(IllegalStateException::class.java)
        } finally {
            writer.close()
            worker.shutdownNow()
        }
    }

    @Test
    fun `missing completion returns false and ordinary tail batches commit`() {
        writer().use { writer ->
            val repository = repository(writer)
            val record = completed(create(repository))
            assertThat(repository.updateProcessingState(record)).isTrue()
            repository.deleteById(record.id)
            assertThat(repository.updateProcessingState(record)).isFalse()
        }
    }
}
