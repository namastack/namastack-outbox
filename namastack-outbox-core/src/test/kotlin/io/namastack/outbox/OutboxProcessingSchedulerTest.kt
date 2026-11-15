package io.namastack.outbox

import io.micrometer.observation.ObservationRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.OutboxProcessingScheduler.SchedulerLifecycleStateMachine.LifecycleState
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.core.task.TaskExecutor
import org.springframework.core.task.support.TaskExecutorAdapter
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.TriggerContext
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
        @BeforeEach
        fun setUp() {
            every { taskScheduler.schedule(any(), trigger) } returns scheduledFuture
            scheduler.start()
        }

        @Test
        fun `process does nothing when no partitions assigned`() {
            every { partitionCoordinator.getAssignedPartitionNumbers() } returns emptySet()

            scheduler.process()

            verify(exactly = 0) {
                recordRepository.findRecordKeysInPartitions(
                    partitions = any(),
                    batchSize = any(),
                    ignoreRecordKeysWithPreviousFailure = any(),
                )
            }
        }

        @Test
        fun `process does nothing when no record keys found`() {
            prepareFindRecordKeysInPartitions(emptyList())

            scheduler.process()

            verify(exactly = 0) { recordRepository.findIncompleteRecordPages(any()) }
        }

        @Test
        fun `process loads record keys from assigned partitions`() {
            val recordKey = "key"

            prepareFindRecordKeysInPartitions(listOf(recordKey))

            prepareRecordPage(recordKey, emptyList())

            scheduler.process()

            verify(exactly = 1) { recordRepository.findIncompleteRecordPages(setOf(recordKey)) }
        }

        @Test
        fun `process handles exception in processing cycle gracefully`() {
            every { partitionCoordinator.getAssignedPartitionNumbers() } throws RuntimeException("Coordinator error")

            scheduler.process()

            verify { partitionCoordinator.getAssignedPartitionNumbers() }
        }

        @Test
        fun `key processing handles exception gracefully`() {
            val recordKey = "test-key"

            prepareFindRecordKeysInPartitions(listOf(recordKey))

            every { recordRepository.findIncompleteRecordPages(setOf(recordKey)) } throws RuntimeException("DB error")

            scheduler.process()

            verify { recordRepository.findIncompleteRecordPages(setOf(recordKey)) }
        }

        @Test
        fun `unavailable payload type pauses polling without reporting cooldown skips to trigger`() {
            val recordKey = "incompatible-key"
            val exception =
                OutboxPayloadTypeNotFoundException(
                    recordId = "record-id",
                    recordKey = recordKey,
                    payloadType = "example.UnavailablePayload",
                    handlerId = "handler-id",
                    cause = ClassNotFoundException("example.UnavailablePayload"),
                )

            prepareFindRecordKeysInPartitions(listOf(recordKey))
            prepareDecodingFailure(recordKey, exception)

            scheduler.process()
            scheduler.process()

            verify(exactly = 1) { partitionCoordinator.getAssignedPartitionNumbers() }
            verify(exactly = 1) { recordRepository.findIncompleteRecordPages(setOf(recordKey)) }
            verify(exactly = 1) { trigger.onPollResult(any(), any()) }

            clock.advanceBy(Duration.ofSeconds(30))
            scheduler.process()

            verify(exactly = 2) { partitionCoordinator.getAssignedPartitionNumbers() }
            verify(exactly = 2) { recordRepository.findIncompleteRecordPages(setOf(recordKey)) }
            verify(exactly = 2) { trigger.onPollResult(any(), any()) }
        }

        @Test
        fun `unavailable handler pauses later polling but lets the current batch finish`() {
            val incompatibleKey = "incompatible-key"
            val compatibleKey = "compatible-key"
            val incompatibleRecord =
                OutboxRecordTestFactory.outboxRecord(
                    recordKey = incompatibleKey,
                    nextRetryAt = Instant.now(clock).minusSeconds(1),
                )
            val incompatibleSuccessor =
                OutboxRecordTestFactory.outboxRecord(
                    recordKey = incompatibleKey,
                    nextRetryAt = Instant.now(clock).minusSeconds(1),
                )
            val compatibleRecord =
                OutboxRecordTestFactory.outboxRecord(
                    recordKey = compatibleKey,
                    nextRetryAt = Instant.now(clock).minusSeconds(1),
                )

            prepareFindRecordKeysInPartitions(listOf(incompatibleKey, compatibleKey))
            prepareRecordPage(
                incompatibleKey,
                listOf(incompatibleRecord, incompatibleSuccessor),
            )
            prepareRecordPage(compatibleKey, listOf(compatibleRecord))
            every { recordProcessorChainInvoker.process(incompatibleRecord) } throws
                OutboxHandlerNotFoundException(
                    recordId = incompatibleRecord.id,
                    recordKey = incompatibleRecord.key,
                    handlerId = incompatibleRecord.handlerId,
                )
            every { recordProcessorChainInvoker.process(compatibleRecord) } returns
                OutboxRecordProcessingOutcome.COMPLETED

            scheduler.process()
            scheduler.process()

            verify(exactly = 1) { recordProcessorChainInvoker.process(incompatibleRecord) }
            verify(exactly = 0) { recordProcessorChainInvoker.process(incompatibleSuccessor) }
            verify(exactly = 1) { recordProcessorChainInvoker.process(compatibleRecord) }
            verify(exactly = 1) { partitionCoordinator.getAssignedPartitionNumbers() }
        }

        @Test
        fun `ordinary processing exception does not pause polling`() {
            val recordKey = "record-key"

            prepareFindRecordKeysInPartitions(listOf(recordKey))
            every { recordRepository.findIncompleteRecordPages(setOf(recordKey)) } throws RuntimeException("DB error")

            scheduler.process()
            scheduler.process()

            verify(exactly = 2) { partitionCoordinator.getAssignedPartitionNumbers() }
            verify(exactly = 2) { recordRepository.findIncompleteRecordPages(setOf(recordKey)) }
        }

        @Test
        fun `process respects batch size configuration`() {
            properties.polling.batchSize = 50
            recreateStartedScheduler()

            prepareFindRecordKeysInPartitions(emptyList())

            scheduler.process()

            verify {
                recordRepository.findRecordKeysInPartitions(
                    partitions = any(),
                    batchSize = 50,
                    ignoreRecordKeysWithPreviousFailure = any(),
                )
            }
        }

        @Test
        fun `process passes stopOnFirstFailure flag to repository`() {
            properties.processing.stopOnFirstFailure = true
            recreateStartedScheduler()

            prepareFindRecordKeysInPartitions(emptyList())

            scheduler.process()

            verify {
                recordRepository.findRecordKeysInPartitions(
                    partitions = any(),
                    batchSize = any(),
                    ignoreRecordKeysWithPreviousFailure = true,
                )
            }
        }

        @Test
        fun `does not invoke processor chain when no incomplete records found for key`() {
            val key = "record-key"

            prepareFindRecordKeysInPartitions(listOf(key))
            prepareRecordPage(
                recordKey = key,
                incompleteRecords = emptyList(),
            )

            scheduler.process()

            verify(exactly = 0) { recordProcessorChainInvoker.process(any()) }
        }

        @Test
        fun `does not invoke processor chain when incomplete records cannot be retried`() {
            val key = "record-key"
            val record =
                OutboxRecordTestFactory.outboxRecord(
                    recordKey = key,
                    nextRetryAt = Instant.now(clock).plusSeconds(5),
                )

            prepareFindRecordKeysInPartitions(listOf(key))
            prepareRecordPage(
                recordKey = key,
                incompleteRecords = listOf(record),
            )

            scheduler.process()

            verify(exactly = 0) { recordProcessorChainInvoker.process(record) }
        }

        @Test
        fun `invokes processor chain when incomplete records found for key`() {
            val key = "record-key"
            val record =
                OutboxRecordTestFactory.outboxRecord(
                    recordKey = key,
                    nextRetryAt = Instant.now(clock).minusSeconds(5),
                )

            prepareFindRecordKeysInPartitions(listOf(key))
            prepareRecordPage(
                recordKey = key,
                incompleteRecords = listOf(record),
            )

            scheduler.process()

            verify(exactly = 1) { recordProcessorChainInvoker.process(record) }
        }

        @Test
        fun `processes multiple records for same key sequentially`() {
            val key = "record-key"
            val record1 =
                OutboxRecordTestFactory.outboxRecord(
                    recordKey = key,
                    nextRetryAt = Instant.now(clock).minusSeconds(5),
                )
            val record2 =
                OutboxRecordTestFactory.outboxRecord(
                    recordKey = key,
                    nextRetryAt = Instant.now(clock).minusSeconds(5),
                )

            prepareFindRecordKeysInPartitions(listOf(key))
            prepareRecordPage(
                recordKey = key,
                incompleteRecords = listOf(record1, record2),
            )

            every { recordProcessorChainInvoker.process(any()) } returns OutboxRecordProcessingOutcome.COMPLETED

            scheduler.process()

            verify(exactly = 1) { recordProcessorChainInvoker.process(record1) }
            verify(exactly = 1) { recordProcessorChainInvoker.process(record2) }
        }

        @Test
        fun `stops processing key when record not ready and stopOnFirstFailure enabled`() {
            properties.processing.stopOnFirstFailure = true
            recreateStartedScheduler()

            val key = "record-key"
            val notReadyRecord =
                OutboxRecordTestFactory.outboxRecord(
                    recordKey = key,
                    nextRetryAt = Instant.now(clock).plusSeconds(5),
                )
            val readyRecord =
                OutboxRecordTestFactory.outboxRecord(
                    recordKey = key,
                    nextRetryAt = Instant.now(clock).minusSeconds(5),
                )

            prepareFindRecordKeysInPartitions(listOf(key))
            prepareRecordPage(
                recordKey = key,
                incompleteRecords = listOf(notReadyRecord, readyRecord),
            )

            scheduler.process()

            verify(exactly = 0) { recordProcessorChainInvoker.process(notReadyRecord) }
            verify(exactly = 0) { recordProcessorChainInvoker.process(readyRecord) }
        }

        @Test
        fun `continues processing when record not ready and stopOnFirstFailure disabled`() {
            properties.processing.stopOnFirstFailure = false
            recreateStartedScheduler()

            val key = "record-key"
            val notReadyRecord =
                OutboxRecordTestFactory.outboxRecord(
                    recordKey = key,
                    nextRetryAt = Instant.now(clock).plusSeconds(5),
                )
            val readyRecord =
                OutboxRecordTestFactory.outboxRecord(
                    recordKey = key,
                    nextRetryAt = Instant.now(clock).minusSeconds(5),
                )

            prepareFindRecordKeysInPartitions(listOf(key))
            prepareRecordPage(
                recordKey = key,
                incompleteRecords = listOf(notReadyRecord, readyRecord),
            )

            every { recordProcessorChainInvoker.process(any()) } returns OutboxRecordProcessingOutcome.COMPLETED

            scheduler.process()

            verify(exactly = 0) { recordProcessorChainInvoker.process(notReadyRecord) }
            verify(exactly = 1) { recordProcessorChainInvoker.process(readyRecord) }
        }

        @Test
        fun `stops processing key when processor chain returns false and stopOnFirstFailure enabled`() {
            properties.processing.stopOnFirstFailure = true
            recreateStartedScheduler()

            val key = "record-key"
            val record1 =
                OutboxRecordTestFactory.outboxRecord(
                    recordKey = key,
                    nextRetryAt = Instant.now(clock).minusSeconds(5),
                )
            val record2 =
                OutboxRecordTestFactory.outboxRecord(
                    recordKey = key,
                    nextRetryAt = Instant.now(clock).minusSeconds(5),
                )

            prepareFindRecordKeysInPartitions(listOf(key))
            prepareRecordPage(
                recordKey = key,
                incompleteRecords = listOf(record1, record2),
            )

            every { recordProcessorChainInvoker.process(record1) } returns
                OutboxRecordProcessingOutcome.RETRY_SCHEDULED
            every { recordProcessorChainInvoker.process(record2) } returns OutboxRecordProcessingOutcome.COMPLETED

            scheduler.process()

            verify(exactly = 1) { recordProcessorChainInvoker.process(record1) }
            verify(exactly = 0) { recordProcessorChainInvoker.process(record2) }
        }

        @Test
        fun `continues processing when processor chain returns false and stopOnFirstFailure disabled`() {
            properties.processing.stopOnFirstFailure = false
            recreateStartedScheduler()

            val key = "record-key"
            val record1 =
                OutboxRecordTestFactory.outboxRecord(
                    recordKey = key,
                    nextRetryAt = Instant.now(clock).minusSeconds(5),
                )
            val record2 =
                OutboxRecordTestFactory.outboxRecord(
                    recordKey = key,
                    nextRetryAt = Instant.now(clock).minusSeconds(5),
                )

            prepareFindRecordKeysInPartitions(listOf(key))
            prepareRecordPage(
                recordKey = key,
                incompleteRecords = listOf(record1, record2),
            )

            every { recordProcessorChainInvoker.process(record1) } returns
                OutboxRecordProcessingOutcome.RETRY_SCHEDULED
            every { recordProcessorChainInvoker.process(record2) } returns OutboxRecordProcessingOutcome.COMPLETED

            scheduler.process()

            verify(exactly = 1) { recordProcessorChainInvoker.process(record1) }
            verify(exactly = 1) { recordProcessorChainInvoker.process(record2) }
        }

        @Test
        fun `processes all ready records when all succeed and stopOnFirstFailure disabled`() {
            properties.processing.stopOnFirstFailure = false
            recreateStartedScheduler()

            val key = "record-key"
            val record1 =
                OutboxRecordTestFactory.outboxRecord(
                    recordKey = key,
                    nextRetryAt = Instant.now(clock).minusSeconds(5),
                )
            val record2 =
                OutboxRecordTestFactory.outboxRecord(
                    recordKey = key,
                    nextRetryAt = Instant.now(clock).minusSeconds(5),
                )
            val record3 =
                OutboxRecordTestFactory.outboxRecord(
                    recordKey = key,
                    nextRetryAt = Instant.now(clock).minusSeconds(5),
                )

            prepareFindRecordKeysInPartitions(listOf(key))
            prepareRecordPage(
                recordKey = key,
                incompleteRecords = listOf(record1, record2, record3),
            )

            every { recordProcessorChainInvoker.process(any()) } returns OutboxRecordProcessingOutcome.COMPLETED

            scheduler.process()

            verify(exactly = 1) { recordProcessorChainInvoker.process(record1) }
            verify(exactly = 1) { recordProcessorChainInvoker.process(record2) }
            verify(exactly = 1) { recordProcessorChainInvoker.process(record3) }
        }
    }

    @Nested
    @DisplayName("Poll results")
    inner class PollResults {
        @BeforeEach
        fun setUp() {
            every { taskScheduler.schedule(any(), trigger) } returns scheduledFuture
        }

        @Test
        fun `batch mode reports the selected keys against the batch size once per run`() {
            scheduler.start()
            prepareFindRecordKeysInPartitions(listOf("key-1", "key-2"))

            scheduler.process()

            verify(exactly = 1) { trigger.onPollResult(any(), any()) }
            verify(exactly = 1) { trigger.onPollResult(2, 100) }
        }

        @Test
        fun `batch mode reports an empty run`() {
            scheduler.start()
            prepareFindRecordKeysInPartitions(emptyList())

            scheduler.process()

            verify(exactly = 1) { trigger.onPollResult(any(), any()) }
            verify(exactly = 1) { trigger.onPollResult(0, 100) }
        }

        @Test
        fun `batch mode reports a failed key query once as nothing found`() {
            scheduler.start()
            every { recordRepository.findRecordKeysInPartitions(any(), any(), any()) } throws
                RuntimeException("database unavailable")

            scheduler.process()

            verify(exactly = 1) { trigger.onPollResult(any(), any()) }
            verify(exactly = 1) { trigger.onPollResult(0, 100) }
        }

        @Test
        fun `batch mode ends the run without processing when the key query fails`() {
            every { recordRepository.findRecordKeysInPartitions(any(), any(), any()) } throws
                RuntimeException("database unavailable")

            batchKeyDispatcher(properties).dispatch(setOf(1)) { false }

            verify(exactly = 0) { recordRepository.findIncompleteRecordPages(any()) }
        }

        @Test
        fun `reports nothing when no partitions are assigned`() {
            scheduler.start()
            every { partitionCoordinator.getAssignedPartitionNumbers() } returns emptySet()

            scheduler.process()

            verify(exactly = 0) { trigger.onPollResult(any(), any()) }
        }

        @Test
        fun `reports nothing when the partition assignment fails`() {
            scheduler.start()
            every { partitionCoordinator.withStableAssignments<Any?>(any()) } throws
                RuntimeException("assignment unavailable")

            scheduler.process()

            verify(exactly = 0) { trigger.onPollResult(any(), any()) }
        }

        @Test
        fun `batch mode reports a failed record load once with the selected keys`() {
            scheduler.start()
            prepareFindRecordKeysInPartitions(listOf("key-1"))
            every { recordRepository.findIncompleteRecordPages(any()) } throws RuntimeException("database unavailable")

            scheduler.process()

            verify(exactly = 1) { trigger.onPollResult(any(), any()) }
            verify(exactly = 1) { trigger.onPollResult(1, 100) }
        }

        @Test
        fun `continuous mode reports each key query once`() {
            every { trigger.nextExecution(any()) } answers { firstArg<TriggerContext>().lastCompletion() }
            scheduler = createContinuousScheduler()
            scheduler.start()
            every {
                recordRepository.findRecordKeysInPartitions(any(), any(), any())
            } returnsMany listOf(listOf("key-1"), emptyList())

            scheduler.process()

            verify(exactly = 1) { trigger.onPollResult(any(), any()) }
            verify(exactly = 1) { trigger.onPollResult(1, 100) }
        }

        @Test
        fun `continuous mode stops dispatching when the scheduler shuts down`() {
            every { trigger.nextExecution(any()) } answers
                { firstArg<TriggerContext>().lastCompletion()!!.plusSeconds(60) }
            val workers = Executors.newCachedThreadPool()
            scheduler = createContinuousScheduler(executor = { workers.execute(it) })
            scheduler.start()
            val recordKey = "slow-key"
            val record =
                OutboxRecordTestFactory.outboxRecord(
                    recordKey = recordKey,
                    nextRetryAt = Instant.now(clock).minusSeconds(1),
                )
            prepareFindRecordKeysInPartitions(listOf(recordKey))
            prepareRecordPage(recordKey, listOf(record))
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            every { recordProcessorChainInvoker.process(record) } answers {
                started.countDown()
                release.await(5, SECONDS)
                OutboxRecordProcessingOutcome.COMPLETED
            }
            val executor = Executors.newFixedThreadPool(2)
            try {
                val processing = executor.submit { scheduler.process() }
                assertThat(started.await(5, SECONDS)).isTrue()
                val stopping = executor.submit { scheduler.stop() }

                await().atMost(2, SECONDS).untilAsserted { assertThat(scheduler.isRunning()).isTrue() }
                release.countDown()
                processing.get(5, SECONDS)
                stopping.get(5, SECONDS)

                assertThat(scheduler.isRunning()).isFalse()
                verify(exactly = 1) { recordRepository.findRecordKeysInPartitions(any(), any(), any()) }
                verify(exactly = 1) { recordProcessorChainInvoker.process(record) }
            } finally {
                release.countDown()
                executor.shutdownNow()
                workers.shutdownNow()
            }
        }
    }

    private fun createContinuousScheduler(executor: TaskExecutor = SyncTaskExecutor()): OutboxProcessingScheduler {
        val settings = properties.toRuntimeSettings()
        return OutboxProcessingScheduler(
            trigger = trigger,
            taskScheduler = taskScheduler,
            observationRegistry = { ObservationRegistry.NOOP },
            partitionCoordinator = partitionCoordinator,
            keyDispatcher =
                ContinuousKeyDispatcher(
                    recordRepository = recordRepository,
                    keyProcessor = OutboxRecordKeyProcessor(recordProcessorChainInvoker, settings.processing, clock),
                    taskExecutor = TaskExecutorAdapter(executor),
                    settings = settings,
                    trigger = trigger,
                    clock = clock,
                ),
            settings = settings,
        )
    }

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

    private fun prepareDecodingFailure(
        recordKey: String,
        failure: RuntimeException,
    ) {
        pagesByKey[recordKey] = LazyOutboxRecordPage(listOf(recordKey)) { throw failure }
    }

    private class MutableClock(
        initialInstant: Instant,
        private val zoneId: ZoneId = ZoneId.of("UTC"),
    ) : Clock() {
        private val currentInstant = AtomicReference(initialInstant)

        override fun getZone(): ZoneId = zoneId

        override fun withZone(zone: ZoneId): Clock = MutableClock(instant(), zone)

        override fun instant(): Instant = currentInstant.get()

        fun advanceBy(duration: Duration) {
            currentInstant.updateAndGet { it.plus(duration) }
        }
    }
}
