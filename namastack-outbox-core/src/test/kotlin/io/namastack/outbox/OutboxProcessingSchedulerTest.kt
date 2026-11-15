package io.namastack.outbox

import io.micrometer.observation.ObservationRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.namastack.outbox.OutboxProcessingScheduler.SchedulerLifecycleStateMachine.LifecycleState
import io.namastack.outbox.dispatch.BatchKeyDispatcher
import io.namastack.outbox.dispatch.OutboxKeyDispatcher
import io.namastack.outbox.dispatch.OutboxRecordKeyProcessor
import io.namastack.outbox.instrumentation.OutboxRecordProcessingOutcome
import io.namastack.outbox.partition.PartitionCoordinator
import io.namastack.outbox.processor.OutboxRecordProcessorChainInvoker
import io.namastack.outbox.runtime.toRuntimeSettings
import io.namastack.outbox.trigger.OutboxPollingTrigger
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
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
        fun getLifecycleState(): LifecycleState {
            val beanStateField = OutboxProcessingScheduler::class.java.getDeclaredField("lifecycle")
            beanStateField.isAccessible = true
            val beanState = beanStateField.get(scheduler)

            val stateField = beanState.javaClass.getDeclaredField("state")
            stateField.isAccessible = true
            val state = stateField.get(beanState) as AtomicReference<*>

            return state.get() as LifecycleState
        }

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

            val allowProcessingToFinish = CountDownLatch(1)
            every { recordProcessorChainInvoker.process(record) } answers {
                allowProcessingToFinish.await(5, SECONDS)
                OutboxRecordProcessingOutcome.COMPLETED
            }

            val executor = Executors.newFixedThreadPool(2)
            try {
                val processFuture = executor.submit<Unit> { scheduler.process() }
                await()
                    .atMost(2, SECONDS)
                    .untilAsserted {
                        assertThat(getLifecycleState()).isEqualTo(LifecycleState.RUNNING)
                    }

                val stopFuture = executor.submit<Unit> { scheduler.stop() }
                await()
                    .atMost(2, SECONDS)
                    .untilAsserted {
                        assertThat(getLifecycleState()).isEqualTo(LifecycleState.SHUTTING_DOWN)
                    }

                allowProcessingToFinish.countDown()
                await()
                    .atMost(2, SECONDS)
                    .untilAsserted {
                        assertThat(getLifecycleState()).isEqualTo(LifecycleState.STOPPED)
                        assertThat(scheduler.isRunning()).isFalse()
                    }

                processFuture.get()
                stopFuture.get()
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

            val allowProcessingToFinish = CountDownLatch(1)
            every { recordProcessorChainInvoker.process(record) } answers {
                allowProcessingToFinish.await(5, SECONDS)
                OutboxRecordProcessingOutcome.COMPLETED
            }

            val executor = Executors.newFixedThreadPool(3)
            try {
                val processFuture = executor.submit<Unit> { scheduler.process() }
                await()
                    .atMost(2, SECONDS)
                    .untilAsserted {
                        assertThat(getLifecycleState()).isEqualTo(LifecycleState.RUNNING)
                    }

                val stopFuture1 = executor.submit<Unit> { scheduler.stop() }
                val stopFuture2 = executor.submit<Unit> { scheduler.stop() }
                await()
                    .atMost(2, SECONDS)
                    .untilAsserted {
                        assertThat(getLifecycleState()).isEqualTo(LifecycleState.SHUTTING_DOWN)
                    }

                allowProcessingToFinish.countDown()
                await()
                    .atMost(2, SECONDS)
                    .untilAsserted {
                        assertThat(getLifecycleState()).isEqualTo(LifecycleState.STOPPED)
                        assertThat(scheduler.isRunning()).isFalse()
                    }

                processFuture.get()
                stopFuture1.get()
                stopFuture2.get()
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

            val allowProcessingToFinish = CountDownLatch(1)
            every { recordProcessorChainInvoker.process(record) } answers {
                allowProcessingToFinish.await(5, SECONDS)
                OutboxRecordProcessingOutcome.COMPLETED
            }

            val executor = Executors.newFixedThreadPool(2)
            try {
                val processFuture = executor.submit<Unit> { scheduler.process() }
                await()
                    .atMost(2, SECONDS)
                    .untilAsserted {
                        assertThat(getLifecycleState()).isEqualTo(LifecycleState.RUNNING)
                    }

                val stopFuture = executor.submit<Unit> { scheduler.stop() }
                await()
                    .atMost(2, SECONDS)
                    .untilAsserted {
                        assertThat(getLifecycleState()).isEqualTo(LifecycleState.STOPPED)
                        assertThat(scheduler.isRunning()).isFalse()
                    }

                stopFuture.get()
                allowProcessingToFinish.countDown()
                processFuture.get(2, SECONDS)
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

            val allowProcessingToFinish = CountDownLatch(1)
            every { recordProcessorChainInvoker.process(record) } answers {
                allowProcessingToFinish.await(5, SECONDS)
                OutboxRecordProcessingOutcome.COMPLETED
            }

            val processExecutor = Executors.newSingleThreadExecutor()
            val stopExecutor = Executors.newSingleThreadExecutor()

            try {
                val processFuture = processExecutor.submit<Unit> { scheduler.process() }
                await()
                    .atMost(2, SECONDS)
                    .untilAsserted {
                        assertThat(getLifecycleState()).isEqualTo(LifecycleState.RUNNING)
                    }

                val stopThreadInterruptedOnExit = AtomicBoolean(false)
                stopExecutor.submit<Unit> {
                    scheduler.stop()
                    stopThreadInterruptedOnExit.set(Thread.currentThread().isInterrupted)
                }
                await()
                    .atMost(2, SECONDS)
                    .untilAsserted {
                        assertThat(getLifecycleState()).isEqualTo(LifecycleState.SHUTTING_DOWN)
                    }

                // Trigger InterruptedException inside awaitProcessingComplete()
                stopExecutor.shutdownNow()

                await()
                    .atMost(2, SECONDS)
                    .untilAsserted {
                        assertThat(getLifecycleState()).isEqualTo(LifecycleState.STOPPED)
                        assertThat(scheduler.isRunning()).isFalse()
                        assertThat(stopThreadInterruptedOnExit.get()).isTrue()
                    }

                allowProcessingToFinish.countDown()
                processFuture.get(2, SECONDS)
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

    private fun schedulerWith(keyDispatcher: OutboxKeyDispatcher): OutboxProcessingScheduler =
        OutboxProcessingScheduler(
            trigger = trigger,
            taskScheduler = taskScheduler,
            observationRegistry = { ObservationRegistry.NOOP },
            partitionCoordinator = partitionCoordinator,
            keyDispatcher = keyDispatcher,
            settings = properties.toRuntimeSettings(),
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
