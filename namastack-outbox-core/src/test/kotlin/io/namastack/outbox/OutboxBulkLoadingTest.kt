package io.namastack.outbox

import io.micrometer.observation.ObservationRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import io.namastack.outbox.handler.invoker.OutboxHandlerInvoker
import io.namastack.outbox.instrumentation.OutboxRecordProcessingOutcome
import io.namastack.outbox.instrumentation.OutboxRecordProcessingOutcome.COMPLETED
import io.namastack.outbox.partition.PartitionCoordinator
import io.namastack.outbox.processor.OutboxRecordProcessorChainInvoker
import io.namastack.outbox.processor.PrimaryOutboxRecordProcessor
import io.namastack.outbox.runtime.OutboxRuntimeSettings
import io.namastack.outbox.trigger.OutboxPollingTrigger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.TaskScheduler
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset.UTC
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class OutboxBulkLoadingTest {
    companion object {
        private const val TEST_TIMEOUT_SECONDS = 10L
        private const val WORKER_TIMEOUT_SECONDS = 30L
    }

    private val now = Instant.parse("2026-01-01T12:00:00Z")
    private val clock = Clock.fixed(now, UTC)
    private val repository = mockk<OutboxRecordRepository>()
    private val invoker = mockk<OutboxRecordProcessorChainInvoker>()
    private val coordinator = mockk<PartitionCoordinator>()
    private val assignmentsReleased = AtomicBoolean()

    @BeforeEach
    fun setUp() {
        every { coordinator.withStableAssignments<Int>(any()) } answers {
            try {
                firstArg<(Set<Int>) -> Int>().invoke(setOf(1))
            } finally {
                assignmentsReleased.set(true)
            }
        }
        selectKeys("a")
        every { invoker.process(any()) } returns COMPLETED
    }

    @ParameterizedTest
    @ValueSource(ints = [10, 1000, 2500])
    fun `automatically splits the cycle batch into portable chunks without per-key reads`(batchSize: Int) {
        val keys = (1..batchSize).map { "key-$it" }
        selectKeys(*keys.toTypedArray())
        every { repository.findIncompleteRecordPages(any()) } answers {
            firstArg<Set<String>>().associateWith { DeferredOutboxRecordPage { emptyList() } }
        }

        scheduler(batchSize = batchSize).process()

        verifyOrder {
            keys.chunked(1000).forEach { chunk -> repository.findIncompleteRecordPages(chunk.toSet()) }
        }
        verify(exactly = (batchSize + 999) / 1000) { repository.findIncompleteRecordPages(any()) }
        verify(exactly = 1) { repository.findRecordKeysInPartitions(setOf(1), OutboxRecordStatus.NEW, batchSize, true) }
        verify(exactly = 0) { repository.findIncompleteRecordsByRecordKey(any()) }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `future retry in the middle is checked before decoding`(strict: Boolean) {
        val first = record("a1")
        val third = record("a3")
        val page = page(first, record("a2"), third)
        every { page.nextRetryAt(1) } returns now.plusSeconds(60)
        every { repository.findIncompleteRecordPages(setOf("a")) } returns mapOf("a" to page)

        scheduler(strict = strict).process()

        verify(exactly = 1) { invoker.process(first) }
        verify(exactly = 0) { page.get(1) }
        verify(exactly = if (strict) 0 else 1) { page.get(2) }
        verify(exactly = if (strict) 0 else 1) { invoker.process(third) }
    }

    @ParameterizedTest
    @EnumSource(OutboxRecordProcessingOutcome::class, names = ["RETRY_SCHEDULED", "FAILED"])
    fun `strict processing does not decode successors after an unsuccessful outcome`(
        outcome: OutboxRecordProcessingOutcome,
    ) {
        val first = record("a1")
        val second = record("a2")
        val page = page(first, second, record("a3"))
        every { repository.findIncompleteRecordPages(setOf("a")) } returns mapOf("a" to page)
        every { invoker.process(second) } returns outcome

        scheduler().process()

        verifyOrder {
            invoker.process(first)
            invoker.process(second)
        }
        verify(exactly = 0) { page.get(2) }
    }

    @Test
    fun `decoding failure leaves earlier work completed and other keys processable`() {
        val first = record("a1")
        val other = record("b1", "b")
        val broken = page(first, record("a2"), record("a3"))
        every { broken.get(1) } throws IllegalArgumentException("Malformed payload")
        selectKeys("a", "b")
        every { repository.findIncompleteRecordPages(setOf("a", "b")) } returns
            mapOf("a" to broken, "b" to page(other))

        scheduler().process()

        verify(exactly = 1) { invoker.process(first) }
        verify(exactly = 1) { invoker.process(other) }
        verify(exactly = 0) { broken.get(2) }
    }

    @Test
    fun `completion errors stop the key before decoding its successor`() {
        val first = record("a1")
        val page = page(first, record("a2"))
        every { repository.findIncompleteRecordPages(setOf("a")) } returns mapOf("a" to page)
        every { invoker.process(first) } throws IllegalStateException("Completion commit failed")

        scheduler().process()

        verify(exactly = 0) { page.get(1) }
        assertThat(assignmentsReleased).isTrue()
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `real primary processing waits for completion before advancing the fetched page`(completionSucceeds: Boolean) {
        val first = record("a1")
        val second = record("a2")
        val page = page(first, second)
        val writeStarted = CountDownLatch(1)
        val finishWrite = CountDownLatch(1)
        val handlers = mockk<OutboxHandlerInvoker>(relaxed = true)
        val chain =
            OutboxRecordProcessorChainInvoker(
                PrimaryOutboxRecordProcessor(
                    handlerInvoker = handlers,
                    recordRepository = repository,
                    settings = OutboxRuntimeSettings.Processing(deleteCompletedRecords = false),
                    clock = clock,
                ),
            )
        every { repository.findIncompleteRecordPages(setOf("a")) } returns mapOf("a" to page)
        every { repository.updateProcessingState(first) } answers {
            writeStarted.countDown()
            check(finishWrite.await(WORKER_TIMEOUT_SECONDS, SECONDS))
            if (!completionSucceeds) throw IllegalStateException("Completion commit failed")
            true
        }
        every { repository.updateProcessingState(second) } returns true
        val processing = Executors.newSingleThreadExecutor()
        try {
            val scheduler = scheduler(chain = chain)
            val future = processing.submit { scheduler.process() }
            assertThat(writeStarted.await(TEST_TIMEOUT_SECONDS, SECONDS)).isTrue()
            verify(exactly = 0) { page.get(1) }
            verify(exactly = 0) { handlers.dispatch(second) }
            assertThat(assignmentsReleased).isFalse()

            finishWrite.countDown()
            future.get(TEST_TIMEOUT_SECONDS, SECONDS)

            verify(exactly = if (completionSucceeds) 1 else 0) { handlers.dispatch(second) }
            if (completionSucceeds) {
                verifyOrder {
                    handlers.dispatch(first)
                    repository.updateProcessingState(first)
                    handlers.dispatch(second)
                    repository.updateProcessingState(second)
                }
            } else {
                assertThat(first.status).isEqualTo(OutboxRecordStatus.NEW)
                verify(exactly = 0) { page.get(1) }
            }
            assertThat(assignmentsReleased).isTrue()
        } finally {
            finishWrite.countDown()
            processing.shutdownNow()
        }
    }

    @Test
    fun `workers run while later chunks are fetched and other keys finish while one is slow`() {
        val firstStarted = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        val first = record("a1")
        val second = record("b1", "b")
        val firstPage = page(first)
        val secondPage = page(second)
        selectKeys(*twoChunkKeys())
        every { repository.findIncompleteRecordPages(match { "a" in it }) } answers {
            firstArg<Set<String>>().associateWith { key ->
                if (key == "a") firstPage else DeferredOutboxRecordPage { emptyList() }
            }
        }
        every { repository.findIncompleteRecordPages(setOf("b")) } answers {
            check(firstStarted.await(TEST_TIMEOUT_SECONDS, SECONDS))
            mapOf("b" to secondPage)
        }
        every { invoker.process(first) } answers {
            firstStarted.countDown()
            check(secondFinished.await(WORKER_TIMEOUT_SECONDS, SECONDS))
            COMPLETED
        }
        every { invoker.process(second) } answers {
            secondFinished.countDown()
            COMPLETED
        }
        val workers = Executors.newFixedThreadPool(2)
        try {
            scheduler(batchSize = 1001, executor = TaskExecutor { workers.execute(it) }).process()
            assertThat(secondFinished.count).isZero()
            assertThat(assignmentsReleased).isTrue()
        } finally {
            secondFinished.countDown()
            workers.shutdownNow()
        }
    }

    @ParameterizedTest
    @EnumSource(DispatchFailure::class)
    fun `assignments remain held until submitted workers finish after a dispatch failure`(failure: DispatchFailure) {
        val workerStarted = CountDownLatch(1)
        val finishWorker = CountDownLatch(1)
        val failureReached = CountDownLatch(1)
        val processingThread = AtomicReference<Thread>()
        val interruptPreserved = AtomicBoolean()
        val submissions = AtomicInteger()
        val first = record("a1")
        val firstPage = page(first)
        val secondPage = page(record("b1", "b"))
        selectKeys(*if (failure == DispatchFailure.INTERRUPTION) arrayOf("a") else twoChunkKeys())
        every { repository.findIncompleteRecordPages(match { "a" in it }) } answers {
            firstArg<Set<String>>().associateWith { key ->
                if (key == "a") firstPage else DeferredOutboxRecordPage { emptyList() }
            }
        }
        every { repository.findIncompleteRecordPages(setOf("b")) } answers {
            check(workerStarted.await(TEST_TIMEOUT_SECONDS, SECONDS))
            if (failure == DispatchFailure.FETCH) {
                failureReached.countDown()
                throw IllegalStateException("Later fetch failed")
            }
            mapOf("b" to secondPage)
        }
        every { invoker.process(first) } answers {
            workerStarted.countDown()
            check(finishWorker.await(WORKER_TIMEOUT_SECONDS, SECONDS))
            COMPLETED
        }
        val workers = Executors.newSingleThreadExecutor()
        val processing = Executors.newSingleThreadExecutor()
        val executor =
            TaskExecutor { task ->
                if (submissions.incrementAndGet() > 1 && failure == DispatchFailure.REJECTION) {
                    failureReached.countDown()
                    throw RejectedExecutionException("Executor is full")
                }
                workers.execute(task)
            }
        try {
            val scheduler = scheduler(batchSize = 1001, executor = executor)
            val future =
                processing.submit {
                    processingThread.set(Thread.currentThread())
                    scheduler.process()
                    interruptPreserved.set(Thread.currentThread().isInterrupted)
                }
            assertThat(workerStarted.await(TEST_TIMEOUT_SECONDS, SECONDS)).isTrue()
            if (failure == DispatchFailure.INTERRUPTION) {
                processingThread.get().interrupt()
            } else {
                assertThat(failureReached.await(TEST_TIMEOUT_SECONDS, SECONDS)).isTrue()
            }
            assertThat(assignmentsReleased).isFalse()
            assertThat(future.isDone).isFalse()
            finishWorker.countDown()
            future.get(TEST_TIMEOUT_SECONDS, SECONDS)
            assertThat(assignmentsReleased).isTrue()
            assertThat(interruptPreserved.get()).isEqualTo(failure == DispatchFailure.INTERRUPTION)
        } finally {
            finishWorker.countDown()
            processing.shutdownNow()
            workers.shutdownNow()
        }
    }

    private fun scheduler(
        batchSize: Int = 10,
        strict: Boolean = true,
        executor: TaskExecutor = SyncTaskExecutor(),
        chain: OutboxRecordProcessorChainInvoker = invoker,
    ): OutboxProcessingScheduler =
        OutboxProcessingScheduler(
            trigger = mockk<OutboxPollingTrigger>(relaxed = true),
            taskScheduler = mockk<TaskScheduler>(relaxed = true),
            observationRegistry = { ObservationRegistry.NOOP },
            recordRepository = repository,
            recordProcessorChainInvoker = chain,
            partitionCoordinator = coordinator,
            taskExecutor = executor,
            settings =
                OutboxRuntimeSettings(
                    polling = OutboxRuntimeSettings.Polling(batchSize = batchSize),
                    processing = OutboxRuntimeSettings.Processing(stopOnFirstFailure = strict),
                ),
            clock = clock,
        ).also { it.start() }

    private fun selectKeys(vararg keys: String) {
        every { repository.findRecordKeysInPartitions(setOf(1), OutboxRecordStatus.NEW, any(), any()) } returns
            keys.toList()
    }

    private fun twoChunkKeys(): Array<String> = (listOf("a") + (1..999).map { "empty-$it" } + "b").toTypedArray()

    private fun record(
        id: String,
        key: String = "a",
    ) = OutboxRecordTestFactory.outboxRecord(id = id, recordKey = key, nextRetryAt = now)

    private fun page(vararg records: OutboxRecord<*>): OutboxRecordPage =
        mockk {
            every { isRetryDue(any(), any()) } answers { callOriginal() }
            every { size } returns records.size
            every { isEmpty() } returns records.isEmpty()
            records.forEachIndexed { index, record ->
                every { get(index) } returns record
                every { nextRetryAt(index) } returns record.nextRetryAt
                every { recordId(index) } returns record.id
            }
        }

    enum class DispatchFailure { FETCH, REJECTION, INTERRUPTION }
}
