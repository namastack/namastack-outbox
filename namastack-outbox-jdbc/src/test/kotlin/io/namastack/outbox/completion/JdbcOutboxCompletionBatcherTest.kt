package io.namastack.outbox.completion

import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxRecordStatus.COMPLETED
import io.namastack.outbox.config.JdbcOutboxConfigurationProperties.CompletionBatching
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Tests completion batching, backpressure, and shutdown behavior.
 *
 * @author Roland Beisel
 */
class JdbcOutboxCompletionBatcherTest {
    private lateinit var executor: ThreadPoolTaskExecutor
    private lateinit var batcher: JdbcOutboxCompletionBatcher

    @AfterEach
    fun tearDown() {
        if (::batcher.isInitialized) batcher.stop()
        if (::executor.isInitialized) executor.shutdown()
    }

    @Test
    fun `writes a full batch and completes each result`() {
        val written = mutableListOf<List<String>>()
        val writer =
            writer { deletions ->
                written.add(deletions.map { it.recordId })
                deletions.map { Result.success(Unit) }
            }
        start(writer, maxBatchSize = 3)

        val results = (1..3).map { batcher.submit(JdbcOutboxCompletion.Delete("record-$it"))!! }
        results.forEach { it.get(2, TimeUnit.SECONDS) }

        assertThat(written).containsExactly(listOf("record-1", "record-2", "record-3"))
    }

    @Test
    fun `routes processing-state completions to the update writer`() {
        val written = mutableListOf<JdbcOutboxCompletion.Update>()
        val writer =
            object : JdbcOutboxCompletionWriter {
                override fun writeUpdates(updates: List<JdbcOutboxCompletion.Update>): List<Result<Unit>> {
                    written.addAll(updates)
                    return updates.map { Result.success(Unit) }
                }

                override fun writeDeletions(deletions: List<JdbcOutboxCompletion.Delete>): List<Result<Unit>> =
                    deletions.map { Result.success(Unit) }
            }
        start(writer)
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val update = JdbcOutboxCompletion.Update("record", COMPLETED, now, 0, null, now)

        batcher.submit(update)!!.get(2, TimeUnit.SECONDS)

        assertThat(written).containsExactly(update)
    }

    @Test
    fun `a mixed batch completes updates and deletions independently`() {
        val writtenUpdates = mutableListOf<String>()
        val writtenDeletions = mutableListOf<String>()
        val deleteFailure = IllegalStateException("Delete failed")
        val writer =
            object : JdbcOutboxCompletionWriter {
                override fun writeUpdates(updates: List<JdbcOutboxCompletion.Update>): List<Result<Unit>> {
                    writtenUpdates.addAll(updates.map { it.recordId })
                    return updates.map { Result.success(Unit) }
                }

                override fun writeDeletions(deletions: List<JdbcOutboxCompletion.Delete>): List<Result<Unit>> {
                    writtenDeletions.addAll(deletions.map { it.recordId })
                    return deletions.map { Result.failure(deleteFailure) }
                }
            }
        start(writer, maxBatchSize = 3, maxWait = Duration.ofSeconds(1))
        val now = Instant.parse("2026-01-01T12:00:00Z")

        val first = batcher.submit(JdbcOutboxCompletion.Update("first", COMPLETED, now, 0, null, now))!!
        val deleted = batcher.submit(JdbcOutboxCompletion.Delete("deleted"))!!
        val last = batcher.submit(JdbcOutboxCompletion.Update("last", COMPLETED, now, 0, null, now))!!

        first.get(2, TimeUnit.SECONDS)
        last.get(2, TimeUnit.SECONDS)
        assertThatThrownBy { deleted.join() }.hasCause(deleteFailure)
        assertThat(writtenUpdates).containsExactly("first", "last")
        assertThat(writtenDeletions).containsExactly("deleted")
    }

    @Test
    fun `another writer processes a completion while one writer is blocked`() {
        val slowWriting = CountDownLatch(1)
        val releaseSlowWrite = CountDownLatch(1)
        val writer =
            writer { deletions ->
                if (deletions.single().recordId == "slow") {
                    slowWriting.countDown()
                    releaseSlowWrite.await(5, TimeUnit.SECONDS)
                }
                deletions.map { Result.success(Unit) }
            }
        start(writer, writerCount = 2)

        val slow = batcher.submit(JdbcOutboxCompletion.Delete("slow"))!!
        assertThat(slowWriting.await(2, TimeUnit.SECONDS)).isTrue()
        val fast = batcher.submit(JdbcOutboxCompletion.Delete("fast"))!!

        try {
            fast.get(2, TimeUnit.SECONDS)
            assertThat(slow.isDone).isFalse()
        } finally {
            releaseSlowWrite.countDown()
        }
        slow.get(2, TimeUnit.SECONDS)
    }

    @Test
    fun `a rejected worker startup never accepts a completion`() {
        val scheduling = CountDownLatch(1)
        val reject = CountDownLatch(1)
        val rejectingExecutor =
            object : ThreadPoolTaskExecutor() {
                override fun execute(task: Runnable) {
                    scheduling.countDown()
                    reject.await(2, TimeUnit.SECONDS)
                    throw IllegalStateException("Worker rejected")
                }
            }
        batcher =
            JdbcOutboxCompletionBatcher(
                writer = writer { deletions -> deletions.map { Result.success(Unit) } },
                executor = rejectingExecutor,
                settings = CompletionBatching(writerCount = 1, maxBatchSize = 1),
                shutdownTimeout = Duration.ofSeconds(2),
            )
        val starting = CompletableFuture.runAsync { batcher.start() }

        assertThat(scheduling.await(2, TimeUnit.SECONDS)).isTrue()
        try {
            assertThat(batcher.isRunning()).isFalse()
            assertThat(batcher.submit(JdbcOutboxCompletion.Delete("record"))).isNull()
        } finally {
            reject.countDown()
        }
        assertThatThrownBy { starting.get(2, TimeUnit.SECONDS) }.hasCauseInstanceOf(IllegalStateException::class.java)
        assertThat(batcher.isRunning()).isFalse()
    }

    @Test
    fun `a full queue blocks submission without failing a completed handler`() {
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer =
            writer { deletions ->
                if (deletions.single().recordId == "first") {
                    writing.countDown()
                    release.await(2, TimeUnit.SECONDS)
                }
                deletions.map { Result.success(Unit) }
            }
        start(writer, maxBatchSize = 1)

        val first = batcher.submit(JdbcOutboxCompletion.Delete("first"))!!
        assertThat(writing.await(2, TimeUnit.SECONDS)).isTrue()
        val second = batcher.submit(JdbcOutboxCompletion.Delete("second"))!!
        val thirdSubmission =
            CompletableFuture.supplyAsync { batcher.submit(JdbcOutboxCompletion.Delete("third")) }

        try {
            assertThatThrownBy { thirdSubmission.get(100, TimeUnit.MILLISECONDS) }
                .isInstanceOf(TimeoutException::class.java)
        } finally {
            release.countDown()
        }

        val third = thirdSubmission.get(2, TimeUnit.SECONDS)!!
        listOf(first, second, third).forEach { it.get(2, TimeUnit.SECONDS) }
    }

    @Test
    fun `shutdown flushes a partial batch before its wait expires`() {
        val writer = writer { deletions -> deletions.map { Result.success(Unit) } }
        start(writer, maxBatchSize = 10, maxWait = Duration.ofSeconds(10))

        val result = batcher.submit(JdbcOutboxCompletion.Delete("record"))!!
        val stopping = CompletableFuture.runAsync { batcher.stop() }

        result.get(2, TimeUnit.SECONDS)
        stopping.get(2, TimeUnit.SECONDS)
        assertThat(batcher.submit(JdbcOutboxCompletion.Delete("late"))).isNull()
    }

    @Test
    fun `shutdown timeout does not report an unfinished database write as failed`() {
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer =
            writer { deletions ->
                writing.countDown()
                release.await(2, TimeUnit.SECONDS)
                deletions.map { Result.success(Unit) }
            }
        start(writer, shutdownTimeout = Duration.ofMillis(50))
        val result = batcher.submit(JdbcOutboxCompletion.Delete("record"))!!
        assertThat(writing.await(2, TimeUnit.SECONDS)).isTrue()

        try {
            batcher.stop()
            assertThat(result.isDone).isFalse()
        } finally {
            release.countDown()
        }

        result.get(2, TimeUnit.SECONDS)
    }

    @Test
    fun `shutdown rejects a blocked submission and drains already queued completions`() {
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer =
            writer { deletions ->
                if (deletions.single().recordId == "first") {
                    writing.countDown()
                    release.await(2, TimeUnit.SECONDS)
                }
                deletions.map { Result.success(Unit) }
            }
        start(writer, maxBatchSize = 1, shutdownTimeout = Duration.ofMillis(50))

        val first = batcher.submit(JdbcOutboxCompletion.Delete("first"))!!
        assertThat(writing.await(2, TimeUnit.SECONDS)).isTrue()
        val second = batcher.submit(JdbcOutboxCompletion.Delete("second"))!!
        val blockedSubmission =
            CompletableFuture.supplyAsync { batcher.submit(JdbcOutboxCompletion.Delete("third")) }

        try {
            assertThatThrownBy { blockedSubmission.get(100, TimeUnit.MILLISECONDS) }
                .isInstanceOf(TimeoutException::class.java)
            batcher.stop()
            assertThat(blockedSubmission.get(2, TimeUnit.SECONDS)).isNull()
            assertThat(first.isDone).isFalse()
        } finally {
            release.countDown()
        }

        first.get(2, TimeUnit.SECONDS)
        second.get(2, TimeUnit.SECONDS)
    }

    @Test
    fun `active caller transactions reject completion before writing`() {
        val repository = mockk<OutboxRecordRepository>()
        val writer = writer { deletions -> deletions.map { Result.success(Unit) } }
        start(writer)
        val persistence = JdbcOutboxCompletionBatchingPersistence(repository, batcher)
        val dataSource = DriverManagerDataSource("jdbc:h2:mem:completion-${UUID.randomUUID()}", "sa", "")
        val transactions = TransactionTemplate(DataSourceTransactionManager(dataSource))
        val record = mockk<OutboxRecord<*>>()

        assertThatThrownBy { transactions.executeWithoutResult { persistence.deleteCompleted("record") } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("JDBC completion batching cannot complete records inside an active transaction")
        assertThatThrownBy { transactions.executeWithoutResult { persistence.updateCompleted(record) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("JDBC completion batching cannot complete records inside an active transaction")
        verify(exactly = 0) { repository.deleteById(any()) }
        verify(exactly = 0) { repository.updateProcessingState(record) }
    }

    @Test
    fun `processing waits for the database result`() {
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer =
            writer { deletions ->
                writing.countDown()
                release.await(2, TimeUnit.SECONDS)
                deletions.map { Result.success(Unit) }
            }
        start(writer, maxBatchSize = 1)
        val persistence = JdbcOutboxCompletionBatchingPersistence(mockk(), batcher)

        val processing = CompletableFuture.runAsync { persistence.deleteCompleted("record") }
        assertThat(writing.await(2, TimeUnit.SECONDS)).isTrue()
        assertThat(processing.isDone).isFalse()
        release.countDown()
        processing.get(2, TimeUnit.SECONDS)
    }

    @Test
    fun `a failed write reaches the processor without failing another completion`() {
        val failure = IllegalStateException("Database rejected the second completion")
        val writer =
            writer { deletions ->
                deletions.map { deletion ->
                    if (deletion.recordId == "second") Result.failure(failure) else Result.success(Unit)
                }
            }
        start(writer, maxBatchSize = 2)

        val first = batcher.submit(JdbcOutboxCompletion.Delete("first"))!!
        val second = batcher.submit(JdbcOutboxCompletion.Delete("second"))!!

        first.get(2, TimeUnit.SECONDS)
        assertThatThrownBy { second.join() }.hasCause(failure)
    }

    @Test
    fun `completions after shutdown use the synchronous repository`() {
        val repository = mockk<OutboxRecordRepository>()
        justRun { repository.deleteById("late") }
        val writer = writer { deletions -> deletions.map { Result.success(Unit) } }
        start(writer)
        val persistence = JdbcOutboxCompletionBatchingPersistence(repository, batcher)
        batcher.stop()

        persistence.deleteCompleted("late")

        verify(exactly = 1) { repository.deleteById("late") }
    }

    private fun start(
        writer: JdbcOutboxCompletionWriter,
        writerCount: Int = 1,
        maxBatchSize: Int = 1,
        maxWait: Duration = Duration.ofMillis(20),
        shutdownTimeout: Duration = Duration.ofSeconds(2),
    ) {
        executor =
            ThreadPoolTaskExecutor().apply {
                corePoolSize = writerCount
                maxPoolSize = writerCount
                queueCapacity = 0
                initialize()
            }
        batcher =
            JdbcOutboxCompletionBatcher(
                writer = writer,
                executor = executor,
                settings =
                    CompletionBatching(
                        writerCount = writerCount,
                        maxBatchSize = maxBatchSize,
                        maxWait = maxWait,
                    ),
                shutdownTimeout = shutdownTimeout,
            )
        batcher.start()
    }

    private fun writer(writeDeletes: (List<JdbcOutboxCompletion.Delete>) -> List<Result<Unit>>) =
        object : JdbcOutboxCompletionWriter {
            override fun writeUpdates(updates: List<JdbcOutboxCompletion.Update>): List<Result<Unit>> =
                updates.map { Result.success(Unit) }

            override fun writeDeletions(deletions: List<JdbcOutboxCompletion.Delete>): List<Result<Unit>> =
                writeDeletes(deletions)
        }
}
