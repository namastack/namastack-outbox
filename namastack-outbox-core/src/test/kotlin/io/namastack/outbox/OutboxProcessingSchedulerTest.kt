package io.namastack.outbox

import io.micrometer.observation.ObservationRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.namastack.outbox.dispatch.BatchKeyDispatcher
import io.namastack.outbox.dispatch.ContinuousKeyDispatcher
import io.namastack.outbox.dispatch.OutboxKeyDispatcher
import io.namastack.outbox.dispatch.OutboxRecordKeyProcessor
import io.namastack.outbox.instrumentation.OutboxRecordProcessingOutcome
import io.namastack.outbox.partition.PartitionCoordinator
import io.namastack.outbox.processor.OutboxRecordProcessorChainInvoker
import io.namastack.outbox.runtime.toRuntimeSettings
import io.namastack.outbox.trigger.OutboxPollingTrigger
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.core.task.support.TaskExecutorAdapter
import org.springframework.scheduling.TaskScheduler
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class OutboxProcessingSchedulerTest {
    private val trigger: OutboxPollingTrigger = mockk(relaxed = true)
    private val taskScheduler: TaskScheduler = mockk(relaxed = true)
    private val scheduledFuture: ScheduledFuture<*> = mockk(relaxed = true)
    private val recordRepository: OutboxRecordRepository = mockk(relaxed = true)
    private val recordProcessorChainInvoker: OutboxRecordProcessorChainInvoker = mockk(relaxed = true)
    private val partitionCoordinator: PartitionCoordinator = mockk(relaxed = true)

    private val fixedInstant = Instant.parse("2024-01-01T10:00:00Z")
    private val clock = MutableClock(fixedInstant)

    private val properties =
        OutboxProperties().apply {
            polling.batchSize = 100
            processing.stopOnFirstFailure = false
        }

    private val pagesByKey = mutableMapOf<String, OutboxRecordPage>()
    private val emptyPage =
        LazyOutboxRecordPage(emptyList<OutboxRecord<*>>()) { it }

    private lateinit var scheduler: OutboxProcessingScheduler

    @BeforeEach
    fun setUp() {
        scheduler = createScheduler()

        every { recordRepository.findIncompleteRecordPages(any()) } answers {
            firstArg<Set<String>>().associateWith { pagesByKey[it] ?: emptyPage }
        }

        every { partitionCoordinator.getAssignedPartitionNumbers() } returns setOf(1)
        every { partitionCoordinator.withStableAssignments<Any?>(any()) } answers {
            firstArg<(Set<Int>) -> Any?>().invoke(partitionCoordinator.getAssignedPartitionNumbers())
        }
    }

    private fun createScheduler(): OutboxProcessingScheduler =
        OutboxProcessingScheduler(
            trigger = trigger,
            taskScheduler = taskScheduler,
            observationRegistry = { ObservationRegistry.NOOP },
            partitionCoordinator = partitionCoordinator,
            keyDispatcher = batchKeyDispatcher(properties),
            settings = properties.toRuntimeSettings(),
        )

    private fun batchKeyDispatcher(properties: OutboxProperties): OutboxKeyDispatcher {
        val settings = properties.toRuntimeSettings()
        return BatchKeyDispatcher(
            recordRepository = recordRepository,
            keyProcessor = OutboxRecordKeyProcessor(recordProcessorChainInvoker, settings.processing, clock),
            taskExecutor = TaskExecutorAdapter(SyncTaskExecutor()),
            settings = settings,
            trigger = trigger,
        )
    }

    private fun recreateStartedScheduler() {
        scheduler = createScheduler()
        scheduler.start()
    }

    @Nested
    @DisplayName("Lifecycle")
    inner class LifecycleTests {
        @Test
        fun `start schedules processing`() {
            every { taskScheduler.schedule(any(), trigger) } returns scheduledFuture

            scheduler.start()

            assertThat(scheduler.isRunning()).isTrue()
            verify(exactly = 1) { taskScheduler.schedule(any(), trigger) }
        }

        @Test
        fun `start schedules processing once`() {
            every { taskScheduler.schedule(any(), trigger) } returns scheduledFuture

            scheduler.start()
            scheduler.start()

            assertThat(scheduler.isRunning()).isTrue()
            verify(exactly = 1) { taskScheduler.schedule(any(), trigger) }
        }

        @Test
        fun `stop do not cancel scheduled task if is was not started`() {
            scheduler.stop()

            assertThat(scheduler.isRunning()).isFalse()
        }

        @Test
        fun `stop cancels scheduled task`() {
            every { taskScheduler.schedule(any(), trigger) } returns scheduledFuture
            scheduler.start()

            scheduler.stop()

            assertThat(scheduler.isRunning()).isFalse()
            verify(exactly = 1) { scheduledFuture.cancel(false) }
        }

        @Test
        fun `stop cancels scheduled task once`() {
            every { taskScheduler.schedule(any(), trigger) } returns scheduledFuture
            scheduler.start()

            scheduler.stop()
            scheduler.stop()

            assertThat(scheduler.isRunning()).isFalse()
            verify(exactly = 1) { scheduledFuture.cancel(false) }
        }

        @Test
        fun `stop waits for running processing to finish before stopping`() {
            every { taskScheduler.schedule(any(), trigger) } returns scheduledFuture
            scheduler.start()

            val recordKey = "record-key"
            val record =
                OutboxRecordTestFactory.outboxRecord(
                    recordKey = recordKey,
                    nextRetryAt = Instant.now(clock).minusSeconds(1),
                )
            prepareFindRecordKeysInPartitions(listOf(recordKey))
            prepareRecordPage(recordKey, listOf(record))

            val processingStarted = CountDownLatch(1)
            val allowProcessingToFinish = CountDownLatch(1)
            every { recordProcessorChainInvoker.process(record) } answers {
                processingStarted.countDown()
                allowProcessingToFinish.await(5, SECONDS)
                OutboxRecordProcessingOutcome.COMPLETED
            }

            val executor = Executors.newFixedThreadPool(2)
            try {
                val processFuture = executor.submit<Unit> { scheduler.process() }
                assertThat(processingStarted.await(2, SECONDS)).isTrue()

                val stopFuture = executor.submit<Unit> { scheduler.stop() }
                assertStaysBlocked(stopFuture)

                allowProcessingToFinish.countDown()
                stopFuture.get(2, SECONDS)
                processFuture.get(2, SECONDS)
                assertThat(scheduler.isRunning()).isFalse()
            } finally {
                executor.shutdownNow()
            }
        }

        @Test
        fun `stop waits for running processing to finish before stopping multi threads`() {
            every { taskScheduler.schedule(any(), trigger) } returns scheduledFuture
            scheduler.start()

            val recordKey = "record-key"
            val record =
                OutboxRecordTestFactory.outboxRecord(
                    recordKey = recordKey,
                    nextRetryAt = Instant.now(clock).minusSeconds(1),
                )
            prepareFindRecordKeysInPartitions(listOf(recordKey))
            prepareRecordPage(recordKey, listOf(record))

            val processingStarted = CountDownLatch(1)
            val allowProcessingToFinish = CountDownLatch(1)
            every { recordProcessorChainInvoker.process(record) } answers {
                processingStarted.countDown()
                allowProcessingToFinish.await(5, SECONDS)
                OutboxRecordProcessingOutcome.COMPLETED
            }

            val executor = Executors.newFixedThreadPool(3)
            try {
                val processFuture = executor.submit<Unit> { scheduler.process() }
                assertThat(processingStarted.await(2, SECONDS)).isTrue()

                val stopFuture1 = executor.submit<Unit> { scheduler.stop() }
                val stopFuture2 = executor.submit<Unit> { scheduler.stop() }
                assertStaysBlocked(stopFuture1)
                assertStaysBlocked(stopFuture2)

                allowProcessingToFinish.countDown()
                stopFuture1.get(2, SECONDS)
                stopFuture2.get(2, SECONDS)
                processFuture.get(2, SECONDS)
                assertThat(scheduler.isRunning()).isFalse()
            } finally {
                executor.shutdownNow()
            }
        }

        @Test
        fun `stop cancels after shutdown timeout when processing is still running`() {
            val properties =
                OutboxProperties().apply {
                    processing.shutdownTimeout = Duration.ofSeconds(0)
                }
            scheduler =
                OutboxProcessingScheduler(
                    trigger = trigger,
                    taskScheduler = taskScheduler,
                    observationRegistry = { ObservationRegistry.NOOP },
                    partitionCoordinator = partitionCoordinator,
                    keyDispatcher = batchKeyDispatcher(properties),
                    settings = properties.toRuntimeSettings(),
                )

            every { taskScheduler.schedule(any(), trigger) } returns scheduledFuture
            scheduler.start()

            val recordKey = "record-key"
            val record =
                OutboxRecordTestFactory.outboxRecord(
                    recordKey = recordKey,
                    nextRetryAt = Instant.now(clock).minusSeconds(1),
                )
            prepareFindRecordKeysInPartitions(listOf(recordKey))
            prepareRecordPage(recordKey, listOf(record))

            val processingStarted = CountDownLatch(1)
            val allowProcessingToFinish = CountDownLatch(1)
            every { recordProcessorChainInvoker.process(record) } answers {
                processingStarted.countDown()
                allowProcessingToFinish.await(5, SECONDS)
                OutboxRecordProcessingOutcome.COMPLETED
            }

            val executor = Executors.newFixedThreadPool(2)
            try {
                val processFuture = executor.submit<Unit> { scheduler.process() }
                assertThat(processingStarted.await(2, SECONDS)).isTrue()

                executor.submit<Unit> { scheduler.stop() }.get(2, SECONDS)

                assertThat(scheduler.isRunning()).isFalse()
                assertThat(processFuture.isDone).describedAs("run outlasted the shutdown timeout").isFalse()

                allowProcessingToFinish.countDown()
                processFuture.get(2, SECONDS)
                assertThat(scheduler.isRunning()).isFalse()
            } finally {
                executor.shutdownNow()
            }
        }

        @Test
        fun `stop cancels after InterruptedException when processing is still running`() {
            every { taskScheduler.schedule(any(), trigger) } returns scheduledFuture
            scheduler.start()

            val recordKey = "record-key"
            val record =
                OutboxRecordTestFactory.outboxRecord(
                    recordKey = recordKey,
                    nextRetryAt = Instant.now(clock).minusSeconds(1),
                )
            prepareFindRecordKeysInPartitions(listOf(recordKey))
            prepareRecordPage(recordKey, listOf(record))

            val processingStarted = CountDownLatch(1)
            val allowProcessingToFinish = CountDownLatch(1)
            every { recordProcessorChainInvoker.process(record) } answers {
                processingStarted.countDown()
                allowProcessingToFinish.await(5, SECONDS)
                OutboxRecordProcessingOutcome.COMPLETED
            }

            val processExecutor = Executors.newSingleThreadExecutor()
            val stopExecutor = Executors.newSingleThreadExecutor()

            try {
                val processFuture = processExecutor.submit<Unit> { scheduler.process() }
                assertThat(processingStarted.await(2, SECONDS)).isTrue()

                val stopThreadInterruptedOnExit = AtomicBoolean(false)
                val stopFuture =
                    stopExecutor.submit<Unit> {
                        scheduler.stop()
                        stopThreadInterruptedOnExit.set(Thread.currentThread().isInterrupted)
                    }
                assertStaysBlocked(stopFuture)

                // Trigger InterruptedException while stop() waits for the run
                stopExecutor.shutdownNow()

                await()
                    .atMost(2, SECONDS)
                    .untilAsserted {
                        assertThat(stopThreadInterruptedOnExit.get()).isTrue()
                    }
                assertThat(scheduler.isRunning()).isFalse()
                assertThat(processFuture.isDone).describedAs("run outlasted the interrupted stop").isFalse()

                allowProcessingToFinish.countDown()
                processFuture.get(2, SECONDS)
                assertThat(scheduler.isRunning()).isFalse()
            } finally {
                processExecutor.shutdownNow()
                stopExecutor.shutdownNow()
            }
        }
    }

    @Nested
    @DisplayName("Process")
    inner class ProcessTestCase {
        private val keyDispatcher = mockk<OutboxKeyDispatcher>(relaxed = true)

        @BeforeEach
        fun setUp() {
            every { taskScheduler.schedule(any(), trigger) } returns scheduledFuture
            every { keyDispatcher.isCompatibilityCooldownActive() } returns false
            scheduler = schedulerWith(keyDispatcher)
            scheduler.start()
        }

        @Test
        fun `dispatches the assigned partitions while the assignment is held`() {
            val assignmentHeld = AtomicBoolean(false)
            val dispatchedWithAssignment = AtomicBoolean(false)
            every { partitionCoordinator.getAssignedPartitionNumbers() } returns setOf(3, 1)
            every { partitionCoordinator.withStableAssignments<Any?>(any()) } answers {
                assignmentHeld.set(true)
                try {
                    firstArg<(Set<Int>) -> Any?>().invoke(partitionCoordinator.getAssignedPartitionNumbers())
                } finally {
                    assignmentHeld.set(false)
                }
            }
            every { keyDispatcher.dispatch(any(), any()) } answers
                { dispatchedWithAssignment.set(assignmentHeld.get()) }

            scheduler.process()

            verify(exactly = 1) { keyDispatcher.dispatch(setOf(1, 3), any()) }
            assertThat(dispatchedWithAssignment.get()).isTrue()
        }

        @Test
        fun `does not dispatch when no partitions are assigned`() {
            every { partitionCoordinator.getAssignedPartitionNumbers() } returns emptySet()

            scheduler.process()

            verify(exactly = 0) { keyDispatcher.dispatch(any(), any()) }
        }

        @Test
        fun `skips the run while a compatibility cooldown is active`() {
            every { keyDispatcher.isCompatibilityCooldownActive() } returnsMany listOf(true, false)

            scheduler.process()

            verify(exactly = 0) { partitionCoordinator.withStableAssignments<Any?>(any()) }
            verify(exactly = 0) { keyDispatcher.dispatch(any(), any()) }

            scheduler.process()

            verify(exactly = 1) { keyDispatcher.dispatch(any(), any()) }
        }

        @Test
        fun `leaves poll results to the dispatcher`() {
            scheduler.process()
            every { partitionCoordinator.getAssignedPartitionNumbers() } returns emptySet()
            scheduler.process()
            every { partitionCoordinator.withStableAssignments<Any?>(any()) } throws RuntimeException("unavailable")
            scheduler.process()

            verify(exactly = 0) { trigger.onPollResult(any(), any()) }
        }

        @Test
        fun `does not start a run after stop`() {
            scheduler.stop()

            scheduler.process()

            verify(exactly = 0) { keyDispatcher.dispatch(any(), any()) }
        }

        @Test
        fun `recovers after a failed run`() {
            every { keyDispatcher.dispatch(any(), any()) } throws RuntimeException("dispatch failed") andThen Unit

            scheduler.process()
            scheduler.process()

            verify(exactly = 2) { keyDispatcher.dispatch(any(), any()) }
        }

        @Test
        fun `recovers after a failed partition assignment lookup`() {
            every { partitionCoordinator.withStableAssignments<Any?>(any()) } throws
                RuntimeException("assignment unavailable") andThenAnswer {
                    firstArg<(Set<Int>) -> Any?>().invoke(setOf(1))
                }

            scheduler.process()
            scheduler.process()

            verify(exactly = 1) { keyDispatcher.dispatch(setOf(1), any()) }
        }

        @Test
        fun `asks the dispatcher to stop when a rebalance is due`() {
            val shouldStop = slot<() -> Boolean>()
            every { keyDispatcher.dispatch(any(), capture(shouldStop)) } returns Unit
            every { partitionCoordinator.isRebalanceDue() } returns false

            scheduler.process()

            assertThat(shouldStop.captured()).isFalse()
            every { partitionCoordinator.isRebalanceDue() } returns true
            assertThat(shouldStop.captured()).isTrue()
        }

        @Test
        fun `asks the dispatcher to stop when shutdown starts during a run`() {
            every { partitionCoordinator.isRebalanceDue() } returns false
            val shouldStop = AtomicReference<() -> Boolean>()
            val release = CountDownLatch(1)
            every { keyDispatcher.dispatch(any(), any()) } answers {
                shouldStop.set(secondArg())
                release.await(5, SECONDS)
            }
            val executor = Executors.newFixedThreadPool(2)
            try {
                val processing = executor.submit { scheduler.process() }
                await().atMost(2, SECONDS).until { shouldStop.get() != null }
                assertThat(shouldStop.get()()).isFalse()

                val stopping = executor.submit { scheduler.stop() }

                await().atMost(2, SECONDS).until { shouldStop.get()() }
                assertThat(stopping.isDone).describedAs("stop waits for the run").isFalse()
                release.countDown()
                processing.get(5, SECONDS)
                stopping.get(5, SECONDS)
                assertThat(scheduler.isRunning()).isFalse()
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Nested
    @DisplayName("Shutdown timeout")
    inner class ShutdownTimeoutTestCase {
        private val shutdownTimeout = Duration.ofMillis(200)
        private val keyDispatcher = mockk<OutboxKeyDispatcher>(relaxed = true)
        private val executor = Executors.newCachedThreadPool()
        private val releaseFirstRun = CountDownLatch(1)
        private val shouldStops = CopyOnWriteArrayList<() -> Boolean>()

        @BeforeEach
        fun setUp() {
            every { taskScheduler.schedule(any(), trigger) } returns scheduledFuture
            every { partitionCoordinator.isRebalanceDue() } returns false
            every { keyDispatcher.isCompatibilityCooldownActive() } returns false
            every { keyDispatcher.dispatch(any(), any()) } answers {
                shouldStops.add(secondArg())
                if (shouldStops.size == 1) releaseFirstRun.await(5, SECONDS)
            }
            scheduler = schedulerWith(keyDispatcher, shutdownTimeout)
            scheduler.start()
        }

        @AfterEach
        fun tearDown() {
            releaseFirstRun.countDown()
            executor.shutdownNow()
        }

        @Test
        fun `keeps asking the run to stop after the shutdown timeout`() {
            val processing = startBlockedRun()

            scheduler.stop()

            assertThat(processing.isDone).describedAs("run outlasted the shutdown timeout").isFalse()
            assertThat(scheduler.isRunning()).isFalse()
            assertThat(shouldStops.single()()).isTrue()

            releaseFirstRun.countDown()
            processing.get(5, SECONDS)
            assertThat(scheduler.isRunning()).isFalse()
        }

        @Test
        fun `keeps asking the run to stop after a restart and starts no run until it ends`() {
            val processing = startBlockedRun()
            scheduler.stop()

            scheduler.start()
            scheduler.process()

            assertThat(scheduler.isRunning()).isTrue()
            assertThat(shouldStops).describedAs("no run started while the previous run is active").hasSize(1)
            assertThat(shouldStops.single()()).isTrue()

            releaseFirstRun.countDown()
            processing.get(5, SECONDS)
            scheduler.process()

            assertThat(shouldStops).hasSize(2)
            assertThat(shouldStops[0]()).describedAs("previous run").isTrue()
            assertThat(shouldStops[1]()).describedAs("next run").isFalse()
        }

        @Test
        fun `stop after a restart waits for the run that outlasted the previous stop`() {
            val processing = startBlockedRun()
            scheduler.stop()
            scheduler.start()

            val stopping = executor.submit { scheduler.stop() }

            assertStaysBlocked(stopping)
            releaseFirstRun.countDown()
            stopping.get(5, SECONDS)
            processing.get(5, SECONDS)
            assertThat(scheduler.isRunning()).isFalse()
        }

        @Test
        fun `continuous dispatch fetches no further key after the shutdown timeout`() {
            val settings =
                OutboxProperties()
                    .apply {
                        polling.batchSize = 1
                        processing.shutdownTimeout = shutdownTimeout
                    }.toRuntimeSettings()
            every { trigger.nextExecution(any()) } returns Instant.now(clock).plus(Duration.ofMinutes(1))

            val pending = CopyOnWriteArrayList(listOf("A", "B"))
            every { recordRepository.findRecordKeysInPartitions(any(), any(), any()) } answers { pending.toList() }
            val recordA = dueRecord("A")
            val recordB = dueRecord("B")
            prepareRecordPage("A", listOf(recordA))
            prepareRecordPage("B", listOf(recordB))

            val processedKeys = CopyOnWriteArrayList<String>()
            val releaseA = CountDownLatch(1)
            every { recordProcessorChainInvoker.process(any()) } answers {
                val record = firstArg<OutboxRecord<*>>()
                if (record.key == "A") releaseA.await(5, SECONDS)
                pending.remove(record.key)
                processedKeys.add(record.key)
                OutboxRecordProcessingOutcome.COMPLETED
            }

            scheduler =
                OutboxProcessingScheduler(
                    trigger = trigger,
                    taskScheduler = taskScheduler,
                    observationRegistry = { ObservationRegistry.NOOP },
                    partitionCoordinator = partitionCoordinator,
                    keyDispatcher =
                        ContinuousKeyDispatcher(
                            recordRepository = recordRepository,
                            keyProcessor =
                                OutboxRecordKeyProcessor(
                                    recordProcessorChainInvoker,
                                    settings.processing,
                                    clock,
                                ),
                            taskExecutor = TaskExecutorAdapter(executor),
                            settings = settings,
                            trigger = trigger,
                            clock = clock,
                        ),
                    settings = settings,
                )
            scheduler.start()

            val processing = executor.submit { scheduler.process() }
            await().atMost(2, SECONDS).untilAsserted {
                verify(exactly = 1) { recordProcessorChainInvoker.process(recordA) }
            }

            scheduler.stop()
            releaseA.countDown()
            processing.get(5, SECONDS)

            assertThat(processedKeys).containsExactly("A")
            verify(exactly = 1) { recordRepository.findRecordKeysInPartitions(any(), any(), any()) }
        }

        private fun startBlockedRun(): Future<*> {
            val processing = executor.submit { scheduler.process() }
            await().atMost(2, SECONDS).until { shouldStops.isNotEmpty() }
            return processing
        }

        private fun dueRecord(recordKey: String): OutboxRecord<*> =
            OutboxRecordTestFactory.outboxRecord(
                recordKey = recordKey,
                nextRetryAt = Instant.now(clock).minusSeconds(1),
            )
    }

    /** Asserts that [future] does not complete for a while, e.g. because stop() waits for a run. */
    private fun assertStaysBlocked(future: Future<*>) {
        await()
            .pollDelay(Duration.ZERO)
            .during(Duration.ofMillis(50))
            .atMost(2, SECONDS)
            .until { !future.isDone }
    }

    private fun schedulerWith(
        keyDispatcher: OutboxKeyDispatcher,
        shutdownTimeout: Duration = properties.processing.shutdownTimeout,
    ): OutboxProcessingScheduler =
        OutboxProcessingScheduler(
            trigger = trigger,
            taskScheduler = taskScheduler,
            observationRegistry = { ObservationRegistry.NOOP },
            partitionCoordinator = partitionCoordinator,
            keyDispatcher = keyDispatcher,
            settings = properties.apply { processing.shutdownTimeout = shutdownTimeout }.toRuntimeSettings(),
        )

    private fun prepareFindRecordKeysInPartitions(recordKeys: List<String>) {
        every {
            recordRepository.findRecordKeysInPartitions(
                partitions = any(),
                batchSize = any(),
                ignoreRecordKeysWithPreviousFailure = any(),
            )
        } returns recordKeys
    }

    private fun prepareRecordPage(
        recordKey: String,
        incompleteRecords: List<OutboxRecord<*>>,
    ) {
        pagesByKey[recordKey] =
            LazyOutboxRecordPage(incompleteRecords) { it }
    }

    private class MutableClock(
        initialInstant: Instant,
        private val zoneId: ZoneId = ZoneId.of("UTC"),
    ) : Clock() {
        private val currentInstant = AtomicReference(initialInstant)

        override fun getZone(): ZoneId = zoneId

        override fun withZone(zone: ZoneId): Clock = MutableClock(instant(), zone)

        override fun instant(): Instant = currentInstant.get()
    }
}
