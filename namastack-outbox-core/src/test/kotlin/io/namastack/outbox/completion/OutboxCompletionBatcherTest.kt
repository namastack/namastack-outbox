package io.namastack.outbox.completion

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxRecordStatus
import io.namastack.outbox.OutboxRecordStatus.COMPLETED
import io.namastack.outbox.runtime.OutboxRuntimeSettings.CompletionBatching
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests completion batching, commit acknowledgement, and shutdown behavior.
 *
 * @author Roland Beisel
 */
class OutboxCompletionBatcherTest {
    private lateinit var executor: ThreadPoolTaskExecutor
    private lateinit var batcher: OutboxCompletionBatcher

    @AfterEach
    fun tearDown() {
        if (::batcher.isInitialized) batcher.stop()
        if (::executor.isInitialized) executor.shutdown()
    }

    @Test
    fun `rejects a negative shutdown timeout`() {
        assertThatThrownBy {
            OutboxCompletionBatcher(
                executor = mockk(),
                shutdownTimeout = Duration.ofNanos(-1),
                writer = mockk(),
                settings = CompletionBatching(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("shutdownTimeout must not be negative")
    }

    @Test
    fun `starting a running batcher does not schedule extra workers`() {
        start(writer { deletions -> deletions.map { Result.success(Unit) } }, writerCount = 2)

        batcher.start()

        assertThat(executor.threadPoolExecutor.taskCount).isEqualTo(2)
        batcher.submit(OutboxCompletion.Delete("record"))!!.get(2, TimeUnit.SECONDS)
        assertThat(batcher.isRunning).isTrue()
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 2])
    fun `a wrong result count fails the completion and the worker remains usable`(resultCount: Int) {
        val attempts = AtomicInteger()
        start(
            writer { deletions ->
                if (attempts.incrementAndGet() == 1) {
                    List(resultCount) { Result.success(Unit) }
                } else {
                    deletions.map { Result.success(Unit) }
                }
            },
        )

        val first = batcher.submit(OutboxCompletion.Delete("first"))!!
        assertThatThrownBy { first.get(2, TimeUnit.SECONDS) }
            .hasCauseInstanceOf(IllegalStateException::class.java)
            .hasRootCauseMessage("Completion writer returned the wrong number of results")
        batcher.submit(OutboxCompletion.Delete("second"))!!.get(2, TimeUnit.SECONDS)
        assertThat(attempts.get()).isEqualTo(2)
    }

    @Test
    fun `a writer error reaches the processor unchanged and later completions still run`() {
        val failure = AssertionError("Writer invariant failed")
        val attempts = AtomicInteger()
        start(
            writer { deletions ->
                if (attempts.incrementAndGet() == 1) throw failure
                deletions.map { Result.success(Unit) }
            },
        )
        val repository = mockk<OutboxRecordRepository>()
        val persistence = OutboxCompletionBatchingPersistence(repository, batcher)

        assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            assertThatThrownBy { persistence.deleteCompleted("first") }.isSameAs(failure)
            persistence.deleteCompleted("second")
        }
        verify(exactly = 0) { repository.deleteById(any()) }
        assertThat(attempts.get()).isEqualTo(2)
    }

    @Test
    fun `borrowed executor drains accepted writes after the shutdown wait expires`() {
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        executor =
            ThreadPoolTaskExecutor().apply {
                corePoolSize = 1
                maxPoolSize = 1
                initialize()
            }
        batcher =
            OutboxCompletionBatcher(
                executor = executor,
                shutdownTimeout = Duration.ZERO,
                writer =
                    writer { deletions ->
                        writing.countDown()
                        release.await()
                        deletions.map { Result.success(Unit) }
                    },
                settings = CompletionBatching(),
            )
        batcher.start()
        val pending = batcher.submit(OutboxCompletion.Delete("record"))!!
        try {
            assertThat(writing.await(2, TimeUnit.SECONDS)).isTrue()
            batcher.stop()
            assertThat(pending.isDone).isFalse()
            assertThat(executor.threadPoolExecutor.isTerminated).isFalse()
            assertThat(executor.threadPoolExecutor.isShutdown).isFalse()
            assertThatThrownBy { batcher.start() }.hasMessage("Previous completion writers are still stopping")
        } finally {
            release.countDown()
        }
        pending.get(2, TimeUnit.SECONDS)
        await().atMost(Duration.ofSeconds(2)).untilAsserted { assertThat(executor.activeCount).isZero() }
    }

    @Test
    fun `batcher can restart using the same borrowed executor`() {
        start(writer { deletions -> deletions.map { Result.success(Unit) } })
        batcher.submit(OutboxCompletion.Delete("first"))!!.get(2, TimeUnit.SECONDS)
        val previousExecutor = executor.threadPoolExecutor

        batcher.stop()
        assertThat(previousExecutor.isShutdown).isFalse()
        batcher.start()

        assertThat(executor.threadPoolExecutor).isSameAs(previousExecutor)
        batcher.submit(OutboxCompletion.Delete("second"))!!.get(2, TimeUnit.SECONDS)
    }

    @Test
    fun `partial worker startup stops accepted workers without closing the borrowed executor`() {
        executor =
            object : ThreadPoolTaskExecutor() {
                private var submissions = 0

                override fun execute(task: Runnable) {
                    if (++submissions == 2) throw IllegalStateException("second worker rejected")
                    super.execute(task)
                }
            }.apply {
                corePoolSize = 2
                maxPoolSize = 2
                initialize()
            }
        batcher =
            OutboxCompletionBatcher(
                executor = executor,
                shutdownTimeout = Duration.ofSeconds(2),
                writer = writer { emptyList() },
                settings = CompletionBatching(writerCount = 2),
            )
        assertThatThrownBy { batcher.start() }.hasMessage("second worker rejected")
        assertThat(executor.threadPoolExecutor.isShutdown).isFalse()
        await().atMost(Duration.ofSeconds(2)).untilAsserted { assertThat(executor.activeCount).isZero() }
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 8])
    fun `writers collect a full batch without splitting submissions between them`(writerCount: Int) {
        val written = ConcurrentLinkedQueue<List<String>>()
        val writer =
            writer { deletions ->
                written.add(deletions.map { it.recordId })
                deletions.map { Result.success(Unit) }
            }
        start(writer, writerCount = writerCount, maxBatchSize = 3, maxWait = Duration.ofSeconds(1))
        await().atMost(Duration.ofSeconds(2)).untilAsserted { assertThat(executor.activeCount).isEqualTo(writerCount) }

        val results = (1..3).map { batcher.submit(OutboxCompletion.Delete("record-$it"))!! }
        results.forEach { it.get(2, TimeUnit.SECONDS) }

        assertThat(written).containsExactly(listOf("record-1", "record-2", "record-3"))
    }

    @Test
    fun `routes processing-state completions to the update writer`() {
        val written = mutableListOf<OutboxCompletion.Update>()
        val writer =
            object : OutboxCompletionWriter {
                override fun writeUpdates(updates: List<OutboxCompletion.Update>): List<Result<Unit>> {
                    written.addAll(updates)
                    return updates.map { Result.success(Unit) }
                }

                override fun writeDeletions(deletions: List<OutboxCompletion.Delete>): List<Result<Unit>> =
                    deletions.map { Result.success(Unit) }
            }
        start(writer)
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val update = OutboxCompletion.Update("record", COMPLETED, now, 0, null, now)

        batcher.submit(update)!!.get(2, TimeUnit.SECONDS)

        assertThat(written).containsExactly(update)
    }

    @Test
    fun `a mixed batch completes updates and deletions independently`() {
        val writtenUpdates = mutableListOf<String>()
        val writtenDeletions = mutableListOf<String>()
        val deleteFailure = IllegalStateException("Delete failed")
        val writer =
            object : OutboxCompletionWriter {
                override fun writeUpdates(updates: List<OutboxCompletion.Update>): List<Result<Unit>> {
                    writtenUpdates.addAll(updates.map { it.recordId })
                    return updates.map { Result.success(Unit) }
                }

                override fun writeDeletions(deletions: List<OutboxCompletion.Delete>): List<Result<Unit>> {
                    writtenDeletions.addAll(deletions.map { it.recordId })
                    return deletions.map { Result.failure(deleteFailure) }
                }
            }
        start(writer, maxBatchSize = 3, maxWait = Duration.ofSeconds(1))
        val now = Instant.parse("2026-01-01T12:00:00Z")

        val first = batcher.submit(OutboxCompletion.Update("first", COMPLETED, now, 0, null, now))!!
        val deleted = batcher.submit(OutboxCompletion.Delete("deleted"))!!
        val last = batcher.submit(OutboxCompletion.Update("last", COMPLETED, now, 0, null, now))!!

        first.get(2, TimeUnit.SECONDS)
        last.get(2, TimeUnit.SECONDS)
        assertThatThrownBy { deleted.join() }.hasCause(deleteFailure)
        assertThat(writtenUpdates).containsExactly("first", "last")
        assertThat(writtenDeletions).containsExactly("deleted")
    }

    @Test
    fun `another writer collects and writes a batch while one writer is blocked`() {
        val slowWriting = CountDownLatch(1)
        val releaseSlowWrite = CountDownLatch(1)
        val written = ConcurrentLinkedQueue<List<String>>()
        val writer =
            writer { deletions ->
                written.add(deletions.map { it.recordId })
                if (deletions.first().recordId == "slow") {
                    slowWriting.countDown()
                    releaseSlowWrite.await(5, TimeUnit.SECONDS)
                }
                deletions.map { Result.success(Unit) }
            }
        start(writer, writerCount = 2, maxBatchSize = 2, maxWait = Duration.ofSeconds(1))

        val slow = batcher.submit(OutboxCompletion.Delete("slow"))!!
        assertThat(slowWriting.await(2, TimeUnit.SECONDS)).isTrue()
        val fast = (1..2).map { batcher.submit(OutboxCompletion.Delete("fast-$it"))!! }

        try {
            fast.forEach { it.get(2, TimeUnit.SECONDS) }
            assertThat(slow.isDone).isFalse()
            assertThat(written).containsExactly(listOf("slow"), listOf("fast-1", "fast-2"))
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
            OutboxCompletionBatcher(
                writer = writer { deletions -> deletions.map { Result.success(Unit) } },
                executor = rejectingExecutor,
                settings = CompletionBatching(writerCount = 1, maxBatchSize = 1),
                shutdownTimeout = Duration.ofSeconds(2),
            )
        val starting = CompletableFuture.runAsync { batcher.start() }

        assertThat(scheduling.await(2, TimeUnit.SECONDS)).isTrue()
        try {
            assertThat(batcher.isRunning()).isFalse()
            assertThat(batcher.submit(OutboxCompletion.Delete("record"))).isNull()
        } finally {
            reject.countDown()
        }
        assertThatThrownBy { starting.get(2, TimeUnit.SECONDS) }.hasCauseInstanceOf(IllegalStateException::class.java)
        assertThat(batcher.isRunning()).isFalse()
    }

    @Test
    fun `submissions do not wait for queue capacity while a writer is busy`() {
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

        val first = batcher.submit(OutboxCompletion.Delete("first"))!!
        assertThat(writing.await(2, TimeUnit.SECONDS)).isTrue()
        val second = batcher.submit(OutboxCompletion.Delete("second"))!!
        val thirdSubmission =
            CompletableFuture.supplyAsync { batcher.submit(OutboxCompletion.Delete("third")) }

        try {
            assertThat(thirdSubmission.get(2, TimeUnit.SECONDS)!!.isDone).isFalse()
        } finally {
            release.countDown()
        }

        val third = thirdSubmission.get(2, TimeUnit.SECONDS)!!
        listOf(first, second, third).forEach { it.get(2, TimeUnit.SECONDS) }
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 8])
    fun `shutdown flushes a partial batch before its wait expires`(writerCount: Int) {
        val writer = writer { deletions -> deletions.map { Result.success(Unit) } }
        start(writer, writerCount = writerCount, maxBatchSize = 10, maxWait = Duration.ofSeconds(10))

        val result = batcher.submit(OutboxCompletion.Delete("record"))!!
        val stopping = CompletableFuture.runAsync { batcher.stop() }

        result.get(2, TimeUnit.SECONDS)
        stopping.get(2, TimeUnit.SECONDS)
        assertThat(batcher.submit(OutboxCompletion.Delete("late"))).isNull()
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 8])
    fun `shutdown drains queued completions in bounded batches without waiting to fill the last batch`(
        writerCount: Int,
    ) {
        val queue = OutboxCompletionQueue()
        val written = ConcurrentLinkedQueue<List<String>>()
        val writer =
            writer { deletions ->
                written.add(deletions.map { it.recordId })
                deletions.map { Result.success(Unit) }
            }
        val worker = OutboxCompletionWorker(queue, writer, 3, Duration.ofSeconds(10))
        queue.startAccepting()
        val results = (1..7).map { queue.submit(OutboxCompletion.Delete("record-$it"))!! }
        queue.stopAccepting()

        val workers = List(writerCount) { CompletableFuture.runAsync { worker.run() } }
        CompletableFuture.allOf(*workers.toTypedArray()).get(2, TimeUnit.SECONDS)

        results.forEach { it.get(2, TimeUnit.SECONDS) }
        assertThat(written).containsExactlyInAnyOrder(
            listOf("record-1", "record-2", "record-3"),
            listOf("record-4", "record-5", "record-6"),
            listOf("record-7"),
        )
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
        val result = batcher.submit(OutboxCompletion.Delete("record"))!!
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
    fun `interrupted shutdown returns without failing an unfinished write`() {
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer =
            writer { deletions ->
                writing.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                deletions.map { Result.success(Unit) }
            }
        start(writer)
        val result = batcher.submit(OutboxCompletion.Delete("record"))!!
        assertThat(writing.await(2, TimeUnit.SECONDS)).isTrue()
        val stopped = CompletableFuture<Boolean>()
        val stoppingThread =
            Thread {
                Thread.currentThread().interrupt()
                batcher.stop()
                stopped.complete(Thread.currentThread().isInterrupted)
            }

        try {
            stoppingThread.start()
            assertThat(stopped.get(2, TimeUnit.SECONDS)).isTrue()
            assertThat(result.isDone).isFalse()
        } finally {
            release.countDown()
            stoppingThread.join(2_000)
        }
        result.get(2, TimeUnit.SECONDS)
    }

    @Test
    fun `shutdown rejects new submissions and drains all already queued completions`() {
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

        val first = batcher.submit(OutboxCompletion.Delete("first"))!!
        assertThat(writing.await(2, TimeUnit.SECONDS)).isTrue()
        val second = batcher.submit(OutboxCompletion.Delete("second"))!!
        val third = batcher.submit(OutboxCompletion.Delete("third"))!!

        try {
            batcher.stop()
            assertThat(batcher.submit(OutboxCompletion.Delete("late"))).isNull()
            assertThat(second.isDone).isFalse()
            assertThat(third.isDone).isFalse()
            assertThat(first.isDone).isFalse()
        } finally {
            release.countDown()
        }

        first.get(2, TimeUnit.SECONDS)
        second.get(2, TimeUnit.SECONDS)
        third.get(2, TimeUnit.SECONDS)
    }

    @Test
    fun `active caller transactions reject completion before writing`() {
        val repository = mockk<OutboxRecordRepository>()
        val writer = writer { deletions -> deletions.map { Result.success(Unit) } }
        start(writer)
        val persistence = OutboxCompletionBatchingPersistence(repository, batcher)
        val record = mockk<OutboxRecord<*>>()

        TransactionSynchronizationManager.setActualTransactionActive(true)
        try {
            assertThatThrownBy { persistence.deleteCompleted("record") }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessage("Completion batching cannot persist processing results inside an active transaction")
            assertThatThrownBy { persistence.updateProcessingState(record) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessage("Completion batching cannot persist processing results inside an active transaction")
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false)
        }
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
        val persistence = OutboxCompletionBatchingPersistence(mockk(), batcher)

        val processing = CompletableFuture.runAsync { persistence.deleteCompleted("record") }
        assertThat(writing.await(2, TimeUnit.SECONDS)).isTrue()
        assertThat(processing.isDone).isFalse()
        release.countDown()
        processing.get(2, TimeUnit.SECONDS)
    }

    @ParameterizedTest
    @EnumSource(OutboxRecordStatus::class)
    fun `update completion snapshots metadata and waits for the committed result`(status: OutboxRecordStatus) {
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        var captured: OutboxCompletion.Update? = null
        val writer =
            object : OutboxCompletionWriter {
                override fun writeUpdates(updates: List<OutboxCompletion.Update>): List<Result<Unit>> {
                    captured = updates.single()
                    writing.countDown()
                    check(release.await(2, TimeUnit.SECONDS))
                    return listOf(Result.success(Unit))
                }

                override fun writeDeletions(deletions: List<OutboxCompletion.Delete>): List<Result<Unit>> =
                    deletions.map { Result.success(Unit) }
            }
        start(writer, maxBatchSize = 1)
        val record = recordWithStatus(status)
        val repository = mockk<OutboxRecordRepository>()
        val persistence = OutboxCompletionBatchingPersistence(repository, batcher)

        val processing = CompletableFuture.runAsync { persistence.updateProcessingState(record) }
        assertThat(writing.await(2, TimeUnit.SECONDS)).isTrue()
        try {
            assertThat(processing.isDone).isFalse()
        } finally {
            release.countDown()
        }
        processing.get(2, TimeUnit.SECONDS)

        assertThat(captured)
            .isEqualTo(
                OutboxCompletion.Update(
                    record.id,
                    status,
                    record.completedAt,
                    record.failureCount,
                    record.failureReason,
                    record.nextRetryAt,
                ),
            )
        verify(exactly = 0) { repository.updateProcessingState(record) }
    }

    @ParameterizedTest
    @EnumSource(OutboxRecordStatus::class)
    fun `failed batched update propagates the database failure without repository fallback`(
        status: OutboxRecordStatus,
    ) {
        val failure = IllegalStateException("Database rejected the update")
        val writer =
            object : OutboxCompletionWriter {
                override fun writeUpdates(updates: List<OutboxCompletion.Update>): List<Result<Unit>> =
                    updates.map { Result.failure(failure) }

                override fun writeDeletions(deletions: List<OutboxCompletion.Delete>): List<Result<Unit>> =
                    deletions.map { Result.success(Unit) }
            }
        start(writer)
        val record = recordWithStatus(status)
        val repository = mockk<OutboxRecordRepository>()
        val persistence = OutboxCompletionBatchingPersistence(repository, batcher)

        assertThatThrownBy { persistence.updateProcessingState(record) }.isSameAs(failure)
        verify(exactly = 0) { repository.updateProcessingState(record) }
    }

    @Test
    fun `checked database failure retains its cause for the processor`() {
        val failure = Exception("Database communication failed")
        val writer =
            writer { deletions -> deletions.map { Result.failure(failure) } }
        start(writer)
        val persistence = OutboxCompletionBatchingPersistence(mockk(), batcher)

        assertThatThrownBy { persistence.deleteCompleted("record") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Completion write failed")
            .hasCause(failure)
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

        val first = batcher.submit(OutboxCompletion.Delete("first"))!!
        val second = batcher.submit(OutboxCompletion.Delete("second"))!!

        first.get(2, TimeUnit.SECONDS)
        assertThatThrownBy { second.join() }.hasCause(failure)
    }

    @Test
    fun `a writer exception fails its batch and later completions still run`() {
        val attempts = AtomicInteger()
        val failure = IllegalStateException("Writer failed")
        val writer =
            writer { deletions ->
                if (attempts.incrementAndGet() == 1) throw failure
                deletions.map { Result.success(Unit) }
            }
        start(writer, maxBatchSize = 1)

        val first = batcher.submit(OutboxCompletion.Delete("first"))!!
        assertThatThrownBy { first.join() }.hasCause(failure)
        batcher.submit(OutboxCompletion.Delete("second"))!!.get(2, TimeUnit.SECONDS)
        assertThat(attempts.get()).isEqualTo(2)
    }

    @Test
    fun `completions after shutdown use the synchronous repository`() {
        val repository = mockk<OutboxRecordRepository>()
        justRun { repository.deleteById("late") }
        val writer = writer { deletions -> deletions.map { Result.success(Unit) } }
        start(writer)
        val persistence = OutboxCompletionBatchingPersistence(repository, batcher)
        batcher.stop()

        persistence.deleteCompleted("late")

        verify(exactly = 1) { repository.deleteById("late") }
    }

    @ParameterizedTest
    @EnumSource(OutboxRecordStatus::class)
    fun `updates after shutdown use the synchronous repository`(status: OutboxRecordStatus) {
        val record = recordWithStatus(status)
        val repository = mockk<OutboxRecordRepository>()
        every { repository.updateProcessingState(record) } returns true
        start(writer { deletions -> deletions.map { Result.success(Unit) } })
        val persistence = OutboxCompletionBatchingPersistence(repository, batcher)
        batcher.stop()

        persistence.updateProcessingState(record)

        verify(exactly = 1) { repository.updateProcessingState(record) }
    }

    private fun recordWithStatus(status: OutboxRecordStatus): OutboxRecord<String> {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        return OutboxRecord.restore(
            id = "record",
            recordKey = "key",
            payload = "payload",
            context = emptyMap(),
            createdAt = now.minusSeconds(60),
            status = status,
            completedAt = if (status == COMPLETED) now else null,
            failureCount = 2,
            failureException = null,
            failureReason = "previous failure",
            partition = 0,
            nextRetryAt = now.plusSeconds(60),
            handlerId = "handler",
        )
    }

    private fun start(
        writer: OutboxCompletionWriter,
        writerCount: Int = 1,
        maxBatchSize: Int = 1,
        maxWait: Duration = Duration.ofMillis(20),
        shutdownTimeout: Duration = Duration.ofSeconds(2),
    ) {
        executor =
            ThreadPoolTaskExecutor().apply {
                corePoolSize = writerCount
                maxPoolSize = writerCount
                initialize()
            }
        batcher =
            OutboxCompletionBatcher(
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

    private fun writer(writeDeletes: (List<OutboxCompletion.Delete>) -> List<Result<Unit>>) =
        object : OutboxCompletionWriter {
            override fun writeUpdates(updates: List<OutboxCompletion.Update>): List<Result<Unit>> =
                updates.map { Result.success(Unit) }

            override fun writeDeletions(deletions: List<OutboxCompletion.Delete>): List<Result<Unit>> =
                writeDeletes(deletions)
        }
}
