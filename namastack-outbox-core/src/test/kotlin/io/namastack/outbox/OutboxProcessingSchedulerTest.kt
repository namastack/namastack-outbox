package io.namastack.outbox

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.partition.PartitionCoordinator
import io.namastack.outbox.processor.OutboxRecordProcessor
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.core.task.SyncTaskExecutor
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class OutboxProcessingSchedulerTest {
    private val recordRepository: OutboxRecordRepository = mockk(relaxed = true)
    private val recordProcessorChain: OutboxRecordProcessor = mockk(relaxed = true)
    private val partitionCoordinator: PartitionCoordinator = mockk(relaxed = true)

    private val fixedInstant = Instant.parse("2024-01-01T10:00:00Z")
    private val clock = Clock.fixed(fixedInstant, ZoneId.of("UTC"))

    private val properties =
        OutboxProperties().apply {
            batchSize = 100
            processing.stopOnFirstFailure = false
        }

    private lateinit var scheduler: OutboxProcessingScheduler

    @BeforeEach
    fun setUp() {
        scheduler =
            OutboxProcessingScheduler(
                recordRepository = recordRepository,
                recordProcessorChain = recordProcessorChain,
                partitionCoordinator = partitionCoordinator,
                taskExecutor = SyncTaskExecutor(),
                properties = properties,
                clock = clock,
            )

        every { partitionCoordinator.getAssignedPartitionNumbers() } returns setOf(1)
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
    fun `process respects batch size configuration`() {
        properties.batchSize = 50

        prepareFindRecordKeysInPartitions(emptyList())

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

        prepareFindRecordKeysInPartitions(emptyList())

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

    private fun prepareFindRecordKeysInPartitions(recordKeys: List<String>) {
        every {
            recordRepository.findRecordKeysInPartitions(
                partitions = any(),
                status = any(),
                batchSize = any(),
                ignoreRecordKeysWithPreviousFailure = any(),
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
