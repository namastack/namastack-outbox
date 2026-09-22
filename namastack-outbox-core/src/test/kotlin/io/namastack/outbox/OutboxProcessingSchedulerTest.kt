package io.namastack.outbox

import io.micrometer.observation.ObservationRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.OutboxProcessingScheduler.SchedulerLifecycleStateMachine.LifecycleState
import io.namastack.outbox.partition.PartitionCoordinator
import io.namastack.outbox.processor.OutboxRecordProcessor
import io.namastack.outbox.trigger.OutboxPollingTrigger
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.core.task.SyncTaskExecutor
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
    private val recordProcessorChain: OutboxRecordProcessor = mockk(relaxed = true)
    private val partitionCoordinator: PartitionCoordinator = mockk(relaxed = true)

    private val fixedInstant = Instant.parse("2024-01-01T10:00:00Z")
    private val clock = Clock.fixed(fixedInstant, ZoneId.of("UTC"))

    private val properties =
        OutboxProperties().apply {
            polling.batchSize = 100
            processing.stopOnFirstFailure = false
        }

    private lateinit var scheduler: OutboxProcessingScheduler

    @BeforeEach
    fun setUp() {
        scheduler =
            OutboxProcessingScheduler(
                trigger = trigger,
                taskScheduler = taskScheduler,
                observationRegistry = { ObservationRegistry.NOOP },
                recordRepository = recordRepository,
                recordProcessorChain = recordProcessorChain,
                partitionCoordinator = partitionCoordinator,
                taskExecutor = SyncTaskExecutor(),
                properties = properties,
                clock = clock,
            )

        every { partitionCoordinator.getAssignedPartitionNumbers() } returns setOf(1)
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
            prepareFindIncompleteRecordsByRecordKey(recordKey, listOf(record))

            val allowProcessingToFinish = CountDownLatch(1)
            every { recordProcessorChain.handle(record) } answers {
                allowProcessingToFinish.await(5, SECONDS)
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
            prepareFindIncompleteRecordsByRecordKey(recordKey, listOf(record))

            val allowProcessingToFinish = CountDownLatch(1)
            every { recordProcessorChain.handle(record) } answers {
                allowProcessingToFinish.await(5, SECONDS)
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
                    recordRepository = recordRepository,
                    recordProcessorChain = recordProcessorChain,
                    partitionCoordinator = partitionCoordinator,
                    taskExecutor = SyncTaskExecutor(),
                    properties = properties,
                    clock = clock,
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
            prepareFindIncompleteRecordsByRecordKey(recordKey, listOf(record))

            val allowProcessingToFinish = CountDownLatch(1)
            every { recordProcessorChain.handle(record) } answers {
                allowProcessingToFinish.await(5, SECONDS)
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
            prepareFindIncompleteRecordsByRecordKey(recordKey, listOf(record))

            val allowProcessingToFinish = CountDownLatch(1)
            every { recordProcessorChain.handle(record) } answers {
                allowProcessingToFinish.await(5, SECONDS)
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
                    status = any(),
                    batchSize = any(),
                    ignoreRecordKeysWithPreviousFailure = any(),
                    compatibilityExclusions = any(),
                )
            }
        }

        @Test
        fun `process does nothing when no record keys found`() {
            prepareFindRecordKeysInPartitions(emptyList())

            scheduler.process()

            verify(exactly = 0) { recordRepository.findIncompleteRecordsByRecordKey(any()) }
        }

        @Test
        fun `process loads record keys from assigned partitions`() {
            val recordKey = "key"

            prepareFindRecordKeysInPartitions(listOf(recordKey))

            every { recordRepository.findIncompleteRecordsByRecordKey(any()) } returns emptyList()

            scheduler.process()

            verify(exactly = 1) { recordRepository.findIncompleteRecordsByRecordKey(recordKey) }
        }

        @Test
        fun `process handles exception in processing cycle gracefully`() {
            every { partitionCoordinator.getAssignedPartitionNumbers() } throws RuntimeException("Coordinator error")

            scheduler.process()

            verify { partitionCoordinator.getAssignedPartitionNumbers() }
        }

        @Test
        fun `processRecordKey handles exception gracefully`() {
            val recordKey = "test-key"

            prepareFindRecordKeysInPartitions(listOf(recordKey))

            every { recordRepository.findIncompleteRecordsByRecordKey(recordKey) } throws RuntimeException("DB error")

            scheduler.process()

            verify { recordRepository.findIncompleteRecordsByRecordKey(recordKey) }
        }

        @Test
        fun `uses learned unavailable payload type to exclude complete record keys`() {
            val payloadTypeAwareRepository = mockk<OutboxRecordRepository>(relaxed = true)
            val observedExclusions = mutableListOf<Pair<Set<String>, Set<String>>>()
            val scheduler =
                OutboxProcessingScheduler(
                    trigger = trigger,
                    taskScheduler = taskScheduler,
                    observationRegistry = { ObservationRegistry.NOOP },
                    recordRepository = payloadTypeAwareRepository,
                    recordProcessorChain = recordProcessorChain,
                    partitionCoordinator = partitionCoordinator,
                    taskExecutor = SyncTaskExecutor(),
                    properties = properties,
                    clock = clock,
                )
            every {
                payloadTypeAwareRepository.findRecordKeysInPartitions(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } answers {
                val exclusions = arg<OutboxCompatibilityExclusions>(4)
                observedExclusions +=
                    exclusions.unavailablePayloadTypes to exclusions.unavailableHandlerIds
                if (exclusions.isEmpty) listOf("blocked-key") else emptyList()
            }
            every {
                payloadTypeAwareRepository.findIncompleteRecordsByRecordKey("blocked-key")
            } throws
                OutboxPayloadTypeNotFoundException(
                    recordId = "record-id",
                    recordKey = "blocked-key",
                    payloadType = "example.MissingPayload",
                    handlerId = "handler-id",
                    cause = ClassNotFoundException("example.MissingPayload"),
                )
            scheduler.start()
            scheduler.process()
            scheduler.process()

            assertThat(observedExclusions)
                .containsExactly(
                    emptySet<String>() to emptySet(),
                    setOf("example.MissingPayload") to emptySet(),
                )
        }

        @Test
        fun `uses learned unavailable handler to exclude complete record keys`() {
            val compatibilityAwareRepository = mockk<OutboxRecordRepository>(relaxed = true)
            val observedExclusions = mutableListOf<Pair<Set<String>, Set<String>>>()
            val scheduler =
                OutboxProcessingScheduler(
                    trigger = trigger,
                    taskScheduler = taskScheduler,
                    observationRegistry = { ObservationRegistry.NOOP },
                    recordRepository = compatibilityAwareRepository,
                    recordProcessorChain = recordProcessorChain,
                    partitionCoordinator = partitionCoordinator,
                    taskExecutor = SyncTaskExecutor(),
                    properties = properties,
                    clock = clock,
                )
            val record =
                OutboxRecordTestFactory.outboxRecord(
                    recordKey = "blocked-key",
                    handlerId = "missing-handler",
                    nextRetryAt = Instant.now(clock).minusSeconds(1),
                )
            every {
                compatibilityAwareRepository.findRecordKeysInPartitions(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } answers {
                val exclusions = arg<OutboxCompatibilityExclusions>(4)
                observedExclusions +=
                    exclusions.unavailablePayloadTypes to exclusions.unavailableHandlerIds
                if (exclusions.isEmpty) listOf(record.key) else emptyList()
            }
            every {
                compatibilityAwareRepository.findIncompleteRecordsByRecordKey(record.key)
            } returns listOf(record)
            every { recordProcessorChain.handle(record) } throws
                OutboxHandlerNotFoundException(
                    recordId = record.id,
                    recordKey = record.key,
                    handlerId = record.handlerId,
                )
            scheduler.start()
            scheduler.process()
            scheduler.process()

            assertThat(record.failureCount).isZero()
            assertThat(record.status).isEqualTo(OutboxRecordStatus.NEW)
            assertThat(observedExclusions)
                .containsExactly(
                    emptySet<String>() to emptySet(),
                    emptySet<String>() to setOf("missing-handler"),
                )
        }

        @Test
        fun `uses learned deserialization failure to exclude its record key`() {
            val compatibilityAwareRepository = mockk<OutboxRecordRepository>(relaxed = true)
            val observedExclusions = mutableListOf<Triple<Set<String>, Set<String>, Set<String>>>()
            val scheduler =
                OutboxProcessingScheduler(
                    trigger = trigger,
                    taskScheduler = taskScheduler,
                    observationRegistry = { ObservationRegistry.NOOP },
                    recordRepository = compatibilityAwareRepository,
                    recordProcessorChain = recordProcessorChain,
                    partitionCoordinator = partitionCoordinator,
                    taskExecutor = SyncTaskExecutor(),
                    properties = properties,
                    clock = clock,
                )
            every {
                compatibilityAwareRepository.findRecordKeysInPartitions(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } answers {
                val exclusions = arg<OutboxCompatibilityExclusions>(4)
                observedExclusions +=
                    Triple(
                        exclusions.unavailablePayloadTypes,
                        exclusions.unavailableHandlerIds,
                        exclusions.unavailableRecordKeys,
                    )
                if (exclusions.isEmpty) listOf("blocked-key") else emptyList()
            }
            every {
                compatibilityAwareRepository.findIncompleteRecordsByRecordKey("blocked-key")
            } throws
                OutboxRecordDeserializationException(
                    recordId = "record-id",
                    recordKey = "blocked-key",
                    payloadType = "example.Payload",
                    handlerId = "handler-id",
                    target = OutboxRecordDeserializationException.Target.PAYLOAD,
                    context = mapOf("traceparent" to "trace-context"),
                    cause = IllegalArgumentException("invalid payload"),
                )
            scheduler.start()
            scheduler.process()
            scheduler.process()

            assertThat(observedExclusions)
                .containsExactly(
                    Triple(emptySet(), emptySet(), emptySet()),
                    Triple(emptySet(), emptySet(), setOf("blocked-key")),
                )
        }

        @Test
        fun `process respects batch size configuration`() {
            properties.polling.batchSize = 50

            prepareFindRecordKeysInPartitions(emptyList())

            scheduler.process()

            verify {
                recordRepository.findRecordKeysInPartitions(
                    partitions = any(),
                    status = any(),
                    batchSize = 50,
                    ignoreRecordKeysWithPreviousFailure = any(),
                    compatibilityExclusions = any(),
                )
            }
        }

        @Test
        fun `process respects batch size deprecated configuration`() {
            properties.polling.batchSize = 100
            properties.batchSize = 50

            prepareFindRecordKeysInPartitions(emptyList())

            scheduler.process()

            verify {
                recordRepository.findRecordKeysInPartitions(
                    partitions = any(),
                    status = any(),
                    batchSize = 50,
                    ignoreRecordKeysWithPreviousFailure = any(),
                    compatibilityExclusions = any(),
                )
            }
        }

        @Test
        fun `process passes stopOnFirstFailure flag to repository`() {
            properties.processing.stopOnFirstFailure = true

            prepareFindRecordKeysInPartitions(emptyList())

            scheduler.process()

            verify {
                recordRepository.findRecordKeysInPartitions(
                    partitions = any(),
                    status = any(),
                    batchSize = any(),
                    ignoreRecordKeysWithPreviousFailure = true,
                    compatibilityExclusions = any(),
                )
            }
        }

        @Test
        fun `does not invoke processor chain when no incomplete records found for key`() {
            val key = "record-key"

            prepareFindRecordKeysInPartitions(listOf(key))
            prepareFindIncompleteRecordsByRecordKey(
                recordKey = key,
                incompleteRecords = emptyList(),
            )

            scheduler.process()

            verify(exactly = 0) { recordProcessorChain.handle(any()) }
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
            prepareFindIncompleteRecordsByRecordKey(
                recordKey = key,
                incompleteRecords = listOf(record),
            )

            scheduler.process()

            verify(exactly = 0) { recordProcessorChain.handle(record) }
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
            prepareFindIncompleteRecordsByRecordKey(
                recordKey = key,
                incompleteRecords = listOf(record),
            )

            scheduler.process()

            verify(exactly = 1) { recordProcessorChain.handle(record) }
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
            prepareFindIncompleteRecordsByRecordKey(
                recordKey = key,
                incompleteRecords = listOf(record1, record2),
            )

            every { recordProcessorChain.handle(any()) } returns true

            scheduler.process()

            verify(exactly = 1) { recordProcessorChain.handle(record1) }
            verify(exactly = 1) { recordProcessorChain.handle(record2) }
        }

        @Test
        fun `stops processing key when record not ready and stopOnFirstFailure enabled`() {
            properties.processing.stopOnFirstFailure = true

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
            prepareFindIncompleteRecordsByRecordKey(
                recordKey = key,
                incompleteRecords = listOf(notReadyRecord, readyRecord),
            )

            scheduler.process()

            verify(exactly = 0) { recordProcessorChain.handle(notReadyRecord) }
            verify(exactly = 0) { recordProcessorChain.handle(readyRecord) }
        }

        @Test
        fun `continues processing when record not ready and stopOnFirstFailure disabled`() {
            properties.processing.stopOnFirstFailure = false

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
            prepareFindIncompleteRecordsByRecordKey(
                recordKey = key,
                incompleteRecords = listOf(notReadyRecord, readyRecord),
            )

            every { recordProcessorChain.handle(any()) } returns true

            scheduler.process()

            verify(exactly = 0) { recordProcessorChain.handle(notReadyRecord) }
            verify(exactly = 1) { recordProcessorChain.handle(readyRecord) }
        }

        @Test
        fun `stops processing key when processor chain returns false and stopOnFirstFailure enabled`() {
            properties.processing.stopOnFirstFailure = true

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
            prepareFindIncompleteRecordsByRecordKey(
                recordKey = key,
                incompleteRecords = listOf(record1, record2),
            )

            every { recordProcessorChain.handle(record1) } returns false
            every { recordProcessorChain.handle(record2) } returns true

            scheduler.process()

            verify(exactly = 1) { recordProcessorChain.handle(record1) }
            verify(exactly = 0) { recordProcessorChain.handle(record2) }
        }

        @Test
        fun `continues processing when processor chain returns false and stopOnFirstFailure disabled`() {
            properties.processing.stopOnFirstFailure = false

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
            prepareFindIncompleteRecordsByRecordKey(
                recordKey = key,
                incompleteRecords = listOf(record1, record2),
            )

            every { recordProcessorChain.handle(record1) } returns false
            every { recordProcessorChain.handle(record2) } returns true

            scheduler.process()

            verify(exactly = 1) { recordProcessorChain.handle(record1) }
            verify(exactly = 1) { recordProcessorChain.handle(record2) }
        }

        @Test
        fun `processes all ready records when all succeed and stopOnFirstFailure disabled`() {
            properties.processing.stopOnFirstFailure = false

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
            prepareFindIncompleteRecordsByRecordKey(
                recordKey = key,
                incompleteRecords = listOf(record1, record2, record3),
            )

            every { recordProcessorChain.handle(any()) } returns true

            scheduler.process()

            verify(exactly = 1) { recordProcessorChain.handle(record1) }
            verify(exactly = 1) { recordProcessorChain.handle(record2) }
            verify(exactly = 1) { recordProcessorChain.handle(record3) }
        }
    }

    private fun prepareFindRecordKeysInPartitions(recordKeys: List<String>) {
        every {
            recordRepository.findRecordKeysInPartitions(
                partitions = any(),
                status = any(),
                batchSize = any(),
                ignoreRecordKeysWithPreviousFailure = any(),
                compatibilityExclusions = match { it.isEmpty },
            )
        } returns recordKeys
    }

    private fun prepareFindIncompleteRecordsByRecordKey(
        recordKey: String,
        incompleteRecords: List<OutboxRecord<*>>,
    ) {
        every { recordRepository.findIncompleteRecordsByRecordKey(recordKey) } returns incompleteRecords
    }
}
