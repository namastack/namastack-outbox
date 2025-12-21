package io.namastack.outbox

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.OutboxRecordTestFactory.outboxRecord
import io.namastack.outbox.partition.PartitionCoordinator
import io.namastack.outbox.processor.OutboxRecordProcessor
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class OutboxProcessingSchedulerTest {
    private lateinit var recordRepository: OutboxRecordRepository
    private lateinit var recordProcessor: OutboxRecordProcessor
    private lateinit var partitionCoordinator: PartitionCoordinator
    private lateinit var properties: OutboxProperties
    private lateinit var clock: Clock
    private lateinit var scheduler: OutboxProcessingScheduler
    private val activeExecutors = mutableListOf<ThreadPoolTaskExecutor>()

    private val fixedInstant = Instant.parse("2024-01-01T10:00:00Z")
    private val fixedOffsetDateTime = OffsetDateTime.ofInstant(fixedInstant, ZoneId.of("UTC"))

    @BeforeEach
    fun setUp() {
        recordRepository = mockk(relaxed = true)
        recordProcessor = mockk(relaxed = true)
        partitionCoordinator = mockk(relaxed = true)
        clock = Clock.fixed(fixedInstant, ZoneId.of("UTC"))

        properties =
            OutboxProperties().apply {
                batchSize = 100
                processing.stopOnFirstFailure = false
            }

        scheduler =
            OutboxProcessingScheduler(
                recordRepository = recordRepository,
                recordProcessorChain = recordProcessor,
                partitionCoordinator = partitionCoordinator,
                taskExecutor = SyncTaskExecutor(),
                properties = properties,
                clock = clock,
            )
    }

    @Test
    fun `process does nothing when no partitions assigned`() {
        every { partitionCoordinator.getAssignedPartitionNumbers() } returns emptySet()

        scheduler.process()

        verify(exactly = 0) { recordRepository.findRecordKeysInPartitions(any(), any(), any(), any()) }
    }

    @Test
    fun `process does nothing when no record keys found`() {
        every { partitionCoordinator.getAssignedPartitionNumbers() } returns setOf(1, 2, 3)
        every {
            recordRepository.findRecordKeysInPartitions(any(), any(), any(), any())
        } returns emptyList()

        scheduler.process()

        verify { recordRepository.findRecordKeysInPartitions(setOf(1, 2, 3), OutboxRecordStatus.NEW, 100, false) }
    }

    @Test
    fun `process loads record keys from assigned partitions`() {
        val assignedPartitions = setOf(1, 2, 3)
        every { partitionCoordinator.getAssignedPartitionNumbers() } returns assignedPartitions
        every {
            recordRepository.findRecordKeysInPartitions(any(), any(), any(), any())
        } returns listOf("key-1")
        every { recordRepository.findIncompleteRecordsByRecordKey(any()) } returns emptyList()

        scheduler.process()

        verify {
            recordRepository.findRecordKeysInPartitions(
                partitions = assignedPartitions,
                status = OutboxRecordStatus.NEW,
                batchSize = 100,
                ignoreRecordKeysWithPreviousFailure = false,
            )
        }
    }

    @Test
    fun `process executes tasks for each record key in parallel`() {
        val (schedulerWithAsync, taskExecutor) = createAsyncScheduler(threadCount = 3)

        val processedKeys = mutableListOf<String>()
        setupBasicMocks(recordKeys = listOf("key-1", "key-2", "key-3"))
        every { recordRepository.findIncompleteRecordsByRecordKey(any()) } answers {
            synchronized(processedKeys) { processedKeys.add(firstArg()) }
            emptyList()
        }

        schedulerWithAsync.process()

        await().atMost(2, TimeUnit.SECONDS).until { processedKeys.size == 3 }
        assertThat(processedKeys).containsExactlyInAnyOrder("key-1", "key-2", "key-3")

        taskExecutor.shutdown()
    }

    @Test
    fun `process waits for all tasks to complete using CountDownLatch`() {
        val (schedulerWithAsync, taskExecutor) = createAsyncScheduler(threadCount = 2)

        var tasksCompleted = 0
        setupBasicMocks(recordKeys = listOf("key-1", "key-2"))
        every { recordRepository.findIncompleteRecordsByRecordKey(any()) } answers {
            Thread.sleep(50)
            synchronized(this) { tasksCompleted++ }
            emptyList()
        }

        schedulerWithAsync.process()

        await().atMost(2, TimeUnit.SECONDS).until { tasksCompleted == 2 }
        assertThat(tasksCompleted).isEqualTo(2)

        taskExecutor.shutdown()
    }

    @Test
    fun `process handles exception in processing cycle gracefully`() {
        every { partitionCoordinator.getAssignedPartitionNumbers() } throws RuntimeException("Coordinator error")

        scheduler.process()

        verify { partitionCoordinator.getAssignedPartitionNumbers() }
    }

    @Test
    fun `processRecordKey loads incomplete records for given key`() {
        val recordKey = "test-key"
        val record =
            outboxRecord(
                recordKey = recordKey,
                nextRetryAt = fixedOffsetDateTime.minusSeconds(1),
                completedAt = null,
            )

        setupBasicMocks(recordKeys = listOf(recordKey))
        every { recordRepository.findIncompleteRecordsByRecordKey(recordKey) } returns listOf(record)
        every { recordProcessor.handle(any()) } returns true

        scheduler.process()

        verify { recordRepository.findIncompleteRecordsByRecordKey(recordKey) }
    }

    @Test
    fun `processRecordKey processes records sequentially`() {
        val recordKey = "test-key"
        val record1 =
            outboxRecord(
                recordKey = recordKey,
                nextRetryAt = fixedOffsetDateTime.minusSeconds(1),
                completedAt = null,
            )
        val record2 =
            outboxRecord(
                recordKey = recordKey,
                nextRetryAt = fixedOffsetDateTime.minusSeconds(1),
                completedAt = null,
            )

        setupBasicMocks(recordKeys = listOf(recordKey))
        every { recordRepository.findIncompleteRecordsByRecordKey(recordKey) } returns listOf(record1, record2)
        every { recordProcessor.handle(any()) } returns true

        scheduler.process()

        verify(exactly = 2) { recordProcessor.handle(any()) }
    }

    @Test
    fun `processRecordKey skips records not ready for retry`() {
        val recordKey = "test-key"
        val futureTime = fixedOffsetDateTime.plusHours(1)
        val record =
            outboxRecord(
                recordKey = recordKey,
                nextRetryAt = futureTime,
                completedAt = null,
            )

        setupBasicMocks(recordKeys = listOf(recordKey))
        every { recordRepository.findIncompleteRecordsByRecordKey(recordKey) } returns listOf(record)

        scheduler.process()

        verify(exactly = 0) { recordProcessor.handle(any()) }
    }

    @Test
    fun `processRecordKey stops on first failure when stopOnFirstFailure is true`() {
        properties.processing.stopOnFirstFailure = true

        val recordKey = "test-key"
        val record1 =
            outboxRecord(
                recordKey = recordKey,
                nextRetryAt = fixedOffsetDateTime.minusSeconds(1),
                completedAt = null,
            )
        val record2 =
            outboxRecord(
                recordKey = recordKey,
                nextRetryAt = fixedOffsetDateTime.minusSeconds(1),
                completedAt = null,
            )
        val record3 =
            outboxRecord(
                recordKey = recordKey,
                nextRetryAt = fixedOffsetDateTime.minusSeconds(1),
                completedAt = null,
            )

        setupBasicMocks(recordKeys = listOf(recordKey))
        every { recordRepository.findIncompleteRecordsByRecordKey(recordKey) } returns listOf(record1, record2, record3)
        every { recordProcessor.handle(record1) } returns true
        every { recordProcessor.handle(record2) } returns false

        scheduler.process()

        verify(exactly = 2) { recordProcessor.handle(any()) }
    }

    @Test
    fun `processRecordKey continues on failure when stopOnFirstFailure is false`() {
        val recordKey = "test-key"
        val record1 =
            outboxRecord(
                recordKey = recordKey,
                nextRetryAt = fixedOffsetDateTime.minusSeconds(1),
                completedAt = null,
            )
        val record2 =
            outboxRecord(
                recordKey = recordKey,
                nextRetryAt = fixedOffsetDateTime.minusSeconds(1),
                completedAt = null,
            )
        val record3 =
            outboxRecord(
                recordKey = recordKey,
                nextRetryAt = fixedOffsetDateTime.minusSeconds(1),
                completedAt = null,
            )

        setupBasicMocks(recordKeys = listOf(recordKey))
        every { recordRepository.findIncompleteRecordsByRecordKey(recordKey) } returns listOf(record1, record2, record3)
        every { recordProcessor.handle(record1) } returns true
        every { recordProcessor.handle(record2) } returns false
        every { recordProcessor.handle(record3) } returns true

        scheduler.process()

        verify(exactly = 3) { recordProcessor.handle(any()) }
    }

    @Test
    fun `processRecordKey handles exception gracefully`() {
        val recordKey = "test-key"

        every { partitionCoordinator.getAssignedPartitionNumbers() } returns setOf(1)
        every {
            recordRepository.findRecordKeysInPartitions(any(), any(), any(), any())
        } returns listOf(recordKey)
        every { recordRepository.findIncompleteRecordsByRecordKey(recordKey) } throws RuntimeException("DB error")

        scheduler.process()

        verify { recordRepository.findIncompleteRecordsByRecordKey(recordKey) }
    }

    @Test
    fun `process respects batch size configuration`() {
        properties.batchSize = 50

        every { partitionCoordinator.getAssignedPartitionNumbers() } returns setOf(1)
        every {
            recordRepository.findRecordKeysInPartitions(any(), any(), any(), any())
        } returns emptyList()

        scheduler.process()

        verify {
            recordRepository.findRecordKeysInPartitions(
                partitions = any(),
                status = any(),
                batchSize = 50,
                ignoreRecordKeysWithPreviousFailure = any(),
            )
        }
    }

    @Test
    fun `process passes stopOnFirstFailure flag to repository`() {
        properties.processing.stopOnFirstFailure = true

        every { partitionCoordinator.getAssignedPartitionNumbers() } returns setOf(1)
        every {
            recordRepository.findRecordKeysInPartitions(any(), any(), any(), any())
        } returns emptyList()

        scheduler.process()

        verify {
            recordRepository.findRecordKeysInPartitions(
                partitions = any(),
                status = any(),
                batchSize = any(),
                ignoreRecordKeysWithPreviousFailure = true,
            )
        }
    }

    @Test
    fun `processRecordKey does nothing when no incomplete records found`() {
        val recordKey = "test-key"

        setupBasicMocks(recordKeys = listOf(recordKey))
        every { recordRepository.findIncompleteRecordsByRecordKey(recordKey) } returns emptyList()

        scheduler.process()

        verify { recordRepository.findIncompleteRecordsByRecordKey(recordKey) }
        verify(exactly = 0) { recordProcessor.handle(any()) }
    }

    @Test
    fun `processRecordKey breaks early when record not ready preserves ordering`() {
        val recordKey = "test-key"
        val futureTime = fixedOffsetDateTime.plusHours(1)
        val record1 =
            outboxRecord(
                recordKey = recordKey,
                nextRetryAt = fixedOffsetDateTime.minusSeconds(1),
                completedAt = null,
            )
        val record2 =
            outboxRecord(
                recordKey = recordKey,
                nextRetryAt = futureTime,
                completedAt = null,
            )
        val record3 =
            outboxRecord(
                recordKey = recordKey,
                nextRetryAt = fixedOffsetDateTime.minusSeconds(1),
                completedAt = null,
            )

        setupBasicMocks(recordKeys = listOf(recordKey))
        every { recordRepository.findIncompleteRecordsByRecordKey(recordKey) } returns listOf(record1, record2, record3)
        every { recordProcessor.handle(record1) } returns true

        scheduler.process()

        verify { recordProcessor.handle(record1) }
        verify(exactly = 0) { recordProcessor.handle(record2) }
        verify(exactly = 0) { recordProcessor.handle(record3) }
    }

    private fun createAsyncScheduler(threadCount: Int): Pair<OutboxProcessingScheduler, ThreadPoolTaskExecutor> {
        val taskExecutor =
            ThreadPoolTaskExecutor().apply {
                corePoolSize = threadCount
                maxPoolSize = threadCount
                initialize()
            }

        activeExecutors.add(taskExecutor)

        val scheduler =
            OutboxProcessingScheduler(
                recordRepository = recordRepository,
                recordProcessorChain = recordProcessor,
                partitionCoordinator = partitionCoordinator,
                taskExecutor = taskExecutor,
                properties = properties,
                clock = clock,
            )

        return scheduler to taskExecutor
    }

    private fun setupBasicMocks(
        partitions: Set<Int> = setOf(1),
        recordKeys: List<String> = emptyList(),
    ) {
        every { partitionCoordinator.getAssignedPartitionNumbers() } returns partitions
        every {
            recordRepository.findRecordKeysInPartitions(
                partitions = any(),
                status = any(),
                batchSize = any(),
                ignoreRecordKeysWithPreviousFailure = any(),
            )
        } returns recordKeys
    }
}
