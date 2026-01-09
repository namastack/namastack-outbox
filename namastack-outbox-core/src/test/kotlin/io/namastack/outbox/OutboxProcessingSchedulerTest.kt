package io.namastack.outbox

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.partition.PartitionCoordinator
import io.namastack.outbox.processor.OutboxRecordProcessor
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.core.task.SyncTaskExecutor
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

class OutboxProcessingSchedulerTest {
    private val recordRepository: OutboxRecordRepository = mockk(relaxed = true)
    private val recordProcessorChain: OutboxRecordProcessor = mockk(relaxed = true)
    private val partitionCoordinator: PartitionCoordinator = mockk(relaxed = true)

    private val fixedInstant = Instant.parse("2024-01-01T10:00:00Z")
    private val clock = Clock.fixed(fixedInstant, ZoneId.of("UTC"))

    private val properties =
        OutboxProperties().apply {
            processing.pollBatchSize = 100
            processing.concurrencyLimit = 100
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
                ignoreRecordKeys = any(),
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
        properties.processing.pollBatchSize = 50

        prepareFindRecordKeysInPartitions(emptyList())

        scheduler.process()

        verify {
            recordRepository.findRecordKeysInPartitions(
                partitions = any(),
                status = any(),
                batchSize = 50,
                ignoreRecordKeys = any(),
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
                ignoreRecordKeys = any(),
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
                nextRetryAt = OffsetDateTime.now(clock).plusSeconds(5),
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
                nextRetryAt = OffsetDateTime.now(clock).minusSeconds(5),
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
                nextRetryAt = OffsetDateTime.now(clock).minusSeconds(5),
            )
        val record2 =
            OutboxRecordTestFactory.outboxRecord(
                recordKey = key,
                nextRetryAt = OffsetDateTime.now(clock).minusSeconds(5),
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
                nextRetryAt = OffsetDateTime.now(clock).plusSeconds(5),
            )
        val readyRecord =
            OutboxRecordTestFactory.outboxRecord(
                recordKey = key,
                nextRetryAt = OffsetDateTime.now(clock).minusSeconds(5),
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
                nextRetryAt = OffsetDateTime.now(clock).plusSeconds(5),
            )
        val readyRecord =
            OutboxRecordTestFactory.outboxRecord(
                recordKey = key,
                nextRetryAt = OffsetDateTime.now(clock).minusSeconds(5),
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
                nextRetryAt = OffsetDateTime.now(clock).minusSeconds(5),
            )
        val record2 =
            OutboxRecordTestFactory.outboxRecord(
                recordKey = key,
                nextRetryAt = OffsetDateTime.now(clock).minusSeconds(5),
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
                nextRetryAt = OffsetDateTime.now(clock).minusSeconds(5),
            )
        val record2 =
            OutboxRecordTestFactory.outboxRecord(
                recordKey = key,
                nextRetryAt = OffsetDateTime.now(clock).minusSeconds(5),
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
                nextRetryAt = OffsetDateTime.now(clock).minusSeconds(5),
            )
        val record2 =
            OutboxRecordTestFactory.outboxRecord(
                recordKey = key,
                nextRetryAt = OffsetDateTime.now(clock).minusSeconds(5),
            )
        val record3 =
            OutboxRecordTestFactory.outboxRecord(
                recordKey = key,
                nextRetryAt = OffsetDateTime.now(clock).minusSeconds(5),
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

    @ParameterizedTest
    @CsvSource(
        value = [
            "0, 1",
            "1, 1",
            "2, 2",
            "3, 2",
            "4, 3",
            "5, 3",
            "6, 4",
            "7, 4",
        ],
    )
    fun `process multiple batches`(
        recordKeyCount: Int,
        batchCount: Int,
    ) {
        properties.processing.pollBatchSize = 2
        properties.processing.concurrencyLimit = 2

        val recordKeys =
            (1..recordKeyCount).map {
                "record-key-$it"
            }
        val records =
            (1..recordKeyCount).map {
                OutboxRecordTestFactory.outboxRecord(
                    id = "record-$it",
                    recordKey = "record-key-$it",
                    nextRetryAt = OffsetDateTime.now(clock).minusSeconds(5),
                )
            }

        prepareFindRecordKeysInPartitions(
            recordKeys.subList(minOf(0, recordKeys.size), minOf(2, recordKeys.size)),
            recordKeys.subList(minOf(2, recordKeys.size), minOf(4, recordKeys.size)),
            recordKeys.subList(minOf(4, recordKeys.size), minOf(6, recordKeys.size)),
            recordKeys.subList(minOf(6, recordKeys.size), minOf(8, recordKeys.size)),
        )
        recordKeys.forEach { recordKey ->
            prepareFindIncompleteRecordsByRecordKey(
                recordKey = recordKey,
                incompleteRecords = records.filter { it.key == recordKey },
            )
        }

        every { recordProcessorChain.handle(any()) } returns true

        scheduler.process()

        verify(exactly = batchCount) {
            recordRepository.findRecordKeysInPartitions(
                partitions = any(),
                status = any(),
                batchSize = any(),
                ignoreRecordKeys = any(),
                ignoreRecordKeysWithPreviousFailure = any(),
            )
        }
        verify(exactly = recordKeyCount) { recordProcessorChain.handle(any()) }
    }

    @Test
    fun `stop processing records if assigned partitions changes`() {
        properties.processing.pollBatchSize = 2
        properties.processing.concurrencyLimit = 2

        val assignedPartitions = setOf(1, 3, 5)

        val recordKeys1 = "record-key-1"
        val recordKeys2 = "record-key-2"

        val record1 =
            OutboxRecordTestFactory.outboxRecord(
                id = "record-1",
                recordKey = recordKeys1,
                nextRetryAt = OffsetDateTime.now(clock).minusSeconds(5),
            )
        val record2 =
            OutboxRecordTestFactory.outboxRecord(
                id = "record-1",
                recordKey = recordKeys2,
                nextRetryAt = OffsetDateTime.now(clock).minusSeconds(5),
            )

        every { partitionCoordinator.getAssignedPartitionNumbers() } returns
            assignedPartitions andThen
            assignedPartitions andThen
            assignedPartitions + 7

        prepareFindRecordKeysInPartitions(listOf(recordKeys1, recordKeys2))
        prepareFindIncompleteRecordsByRecordKey(
            recordKey = recordKeys1,
            incompleteRecords = listOf(record1),
        )
        prepareFindIncompleteRecordsByRecordKey(
            recordKey = recordKeys2,
            incompleteRecords = listOf(record2),
        )

        every { recordProcessorChain.handle(any()) } returns true

        scheduler.process()

        verify(exactly = 1) {
            recordRepository.findRecordKeysInPartitions(
                partitions = any(),
                status = any(),
                batchSize = any(),
                ignoreRecordKeys = any(),
                ignoreRecordKeysWithPreviousFailure = any(),
            )
        }
        verify(exactly = 2) { recordProcessorChain.handle(any()) }
    }

    private fun prepareFindRecordKeysInPartitions(vararg recordKeys: List<String>) {
        every {
            recordRepository.findRecordKeysInPartitions(
                partitions = any(),
                status = any(),
                batchSize = any(),
                ignoreRecordKeys = any(),
                ignoreRecordKeysWithPreviousFailure = any(),
            )
        } returnsMany recordKeys.toList()
    }

    private fun prepareFindIncompleteRecordsByRecordKey(
        recordKey: String,
        incompleteRecords: List<OutboxRecord<*>>,
    ) {
        every { recordRepository.findIncompleteRecordsByRecordKey(recordKey) } returns incompleteRecords
    }
}
