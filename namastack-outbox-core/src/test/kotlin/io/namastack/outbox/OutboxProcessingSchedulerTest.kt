package io.namastack.outbox

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.OutboxRecordStatus.COMPLETED
import io.namastack.outbox.OutboxRecordStatus.FAILED
import io.namastack.outbox.OutboxRecordStatus.NEW
import io.namastack.outbox.OutboxRecordTestFactory.outboxRecord
import io.namastack.outbox.partition.PartitionCoordinator
import io.namastack.outbox.retry.OutboxRetryPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@DisplayName("OutboxProcessingScheduler")
class OutboxProcessingSchedulerTest {
    private val clock = Clock.fixed(Instant.parse("2025-09-25T10:00:00Z"), ZoneOffset.UTC)
    private val now = OffsetDateTime.now(clock)

    private val recordRepository = mockk<OutboxRecordRepository>()
    private val recordProcessor = mockk<OutboxRecordProcessor>()
    private val partitionCoordinator = mockk<PartitionCoordinator>()
    private val instanceRegistry = mockk<OutboxInstanceRegistry>()
    private val retryPolicy = mockk<OutboxRetryPolicy>()

    private val properties =
        OutboxProperties(
            batchSize = 100,
            processing = OutboxProperties.Processing(stopOnFirstFailure = true),
            retry = OutboxProperties.Retry(maxRetries = 3),
        )

    private lateinit var scheduler: OutboxProcessingScheduler

    @BeforeEach
    fun setUp() {
        scheduler =
            OutboxProcessingScheduler(
                recordRepository = recordRepository,
                recordProcessor = recordProcessor,
                partitionCoordinator = partitionCoordinator,
                instanceRegistry = instanceRegistry,
                retryPolicy = retryPolicy,
                properties = properties,
                clock = clock,
            )

        every { instanceRegistry.getCurrentInstanceId() } returns "test-instance"
        every { recordProcessor.process(any()) } returns Unit
        every { retryPolicy.shouldRetry(any()) } returns true
        every { retryPolicy.nextDelay(any()) } returns Duration.ofSeconds(10)
    }

    @Nested
    @DisplayName("Partition Processing")
    inner class PartitionProcessing {
        @Test
        fun `skip processing when no partitions assigned`() {
            every { partitionCoordinator.getAssignedPartitions("test-instance") } returns emptyList()

            scheduler.process()

            verify(exactly = 0) { recordRepository.findAggregateIdsInPartitions(any(), any(), any()) }
        }

        @Test
        fun `process records from assigned partitions`() {
            val assignedPartitions = listOf(1, 3, 5)

            every { partitionCoordinator.getAssignedPartitions("test-instance") } returns assignedPartitions
            every { recordRepository.findAggregateIdsInPartitions(listOf(1, 3, 5), NEW, 100) } returns emptyList()

            scheduler.process()

            verify(exactly = 1) { recordRepository.findAggregateIdsInPartitions(listOf(1, 3, 5), NEW, 100) }
        }

        @Test
        fun `respect batch size configuration`() {
            val customProperties = properties.copy(batchSize = 50)
            val customScheduler =
                OutboxProcessingScheduler(
                    recordRepository,
                    recordProcessor,
                    partitionCoordinator,
                    instanceRegistry,
                    retryPolicy,
                    customProperties,
                    clock,
                )

            every { partitionCoordinator.getAssignedPartitions("test-instance") } returns listOf(1, 2)
            every { recordRepository.findAggregateIdsInPartitions(listOf(1, 2), NEW, 50) } returns emptyList()

            customScheduler.process()

            verify(exactly = 1) { recordRepository.findAggregateIdsInPartitions(listOf(1, 2), NEW, 50) }
        }
    }

    @Nested
    @DisplayName("Aggregate Processing")
    inner class AggregateProcessing {
        @Test
        fun `process single record successfully`() {
            val record =
                outboxRecord(
                    aggregateId = "aggregate-1",
                    status = NEW,
                    nextRetryAt = now.minusMinutes(1),
                )

            every { partitionCoordinator.getAssignedPartitions("test-instance") } returns listOf(1)
            every { recordRepository.findAggregateIdsInPartitions(listOf(1), NEW, 100) } returns listOf("aggregate-1")
            every { recordRepository.findAllIncompleteRecordsByAggregateId("aggregate-1") } returns listOf(record)
            every { recordRepository.save(any()) } returns record

            scheduler.process()

            verify(exactly = 1) { recordProcessor.process(record) }
            verify(exactly = 1) { recordRepository.save(record) }
            assertThat(record.status).isEqualTo(COMPLETED)
            assertThat(record.completedAt).isNotNull()
        }

        @Test
        fun `process multiple records in order`() {
            val record1 =
                outboxRecord(
                    id = "record-1",
                    aggregateId = "aggregate-1",
                    createdAt = now.minusMinutes(2),
                    nextRetryAt = now.minusMinutes(1),
                )
            val record2 =
                outboxRecord(
                    id = "record-2",
                    aggregateId = "aggregate-1",
                    createdAt = now.minusMinutes(1),
                    nextRetryAt = now.minusMinutes(1),
                )

            every { partitionCoordinator.getAssignedPartitions("test-instance") } returns listOf(1)
            every { recordRepository.findAggregateIdsInPartitions(listOf(1), NEW, 100) } returns listOf("aggregate-1")
            every { recordRepository.findAllIncompleteRecordsByAggregateId("aggregate-1") } returns
                listOf(record1, record2)
            every { recordRepository.save(any()) } returns mockk()

            scheduler.process()

            verify(exactly = 1) { recordProcessor.process(record1) }
            verify(exactly = 1) { recordProcessor.process(record2) }
        }

        @Test
        fun `skip records not ready for retry`() {
            val futureRecord =
                outboxRecord(
                    aggregateId = "aggregate-1",
                    nextRetryAt = now.plusMinutes(5),
                )

            every { partitionCoordinator.getAssignedPartitions("test-instance") } returns listOf(1)
            every { recordRepository.findAggregateIdsInPartitions(listOf(1), NEW, 100) } returns listOf("aggregate-1")
            every { recordRepository.findAllIncompleteRecordsByAggregateId("aggregate-1") } returns listOf(futureRecord)

            scheduler.process()

            verify(exactly = 0) { recordProcessor.process(any()) }
        }

        @Test
        fun `handle empty aggregate`() {
            every { partitionCoordinator.getAssignedPartitions("test-instance") } returns listOf(1)
            every { recordRepository.findAggregateIdsInPartitions(listOf(1), NEW, 100) } returns listOf("aggregate-1")
            every { recordRepository.findAllIncompleteRecordsByAggregateId("aggregate-1") } returns emptyList()

            scheduler.process()

            verify(exactly = 0) { recordProcessor.process(any()) }
        }
    }

    @Nested
    @DisplayName("Record Processing")
    inner class RecordProcessing {
        @Test
        fun `mark record as completed on success`() {
            val record = outboxRecord(status = NEW, nextRetryAt = now.minusMinutes(1))

            every { partitionCoordinator.getAssignedPartitions("test-instance") } returns listOf(1)
            every { recordRepository.findAggregateIdsInPartitions(listOf(1), NEW, 100) } returns listOf("aggregate-1")
            every { recordRepository.findAllIncompleteRecordsByAggregateId("aggregate-1") } returns listOf(record)
            every { recordRepository.save(any()) } returns record

            scheduler.process()

            assertThat(record.status).isEqualTo(COMPLETED)
            assertThat(record.completedAt).isEqualTo(now)
        }

        @Test
        fun `increment retry count on failure`() {
            val record = outboxRecord(status = NEW, retryCount = 0, nextRetryAt = now.minusMinutes(1))

            every { partitionCoordinator.getAssignedPartitions("test-instance") } returns listOf(1)
            every { recordRepository.findAggregateIdsInPartitions(listOf(1), NEW, 100) } returns listOf("aggregate-1")
            every { recordRepository.findAllIncompleteRecordsByAggregateId("aggregate-1") } returns listOf(record)
            every { recordProcessor.process(record) } throws RuntimeException("Processing failed")
            every { recordRepository.save(any()) } returns record

            scheduler.process()

            assertThat(record.retryCount).isEqualTo(1)
            assertThat(record.nextRetryAt).isEqualTo(now.plus(Duration.ofSeconds(10)))
            assertThat(record.status).isEqualTo(NEW)
        }

        @Test
        fun `mark as failed after max retries`() {
            val record = outboxRecord(status = NEW, retryCount = 3, nextRetryAt = now.minusMinutes(1))

            every { partitionCoordinator.getAssignedPartitions("test-instance") } returns listOf(1)
            every { recordRepository.findAggregateIdsInPartitions(listOf(1), NEW, 100) } returns listOf("aggregate-1")
            every { recordRepository.findAllIncompleteRecordsByAggregateId("aggregate-1") } returns listOf(record)
            every { recordProcessor.process(record) } throws RuntimeException("Processing failed")
            every { recordRepository.save(any()) } returns record

            scheduler.process()

            assertThat(record.retryCount).isEqualTo(4)
            assertThat(record.status).isEqualTo(FAILED)
        }

        @Test
        fun `mark as failed when retry policy rejects`() {
            val record = outboxRecord(status = NEW, retryCount = 0, nextRetryAt = now.minusMinutes(1))
            val exception = RuntimeException("Non-retryable error")

            every { partitionCoordinator.getAssignedPartitions("test-instance") } returns listOf(1)
            every { recordRepository.findAggregateIdsInPartitions(listOf(1), NEW, 100) } returns listOf("aggregate-1")
            every { recordRepository.findAllIncompleteRecordsByAggregateId("aggregate-1") } returns listOf(record)
            every { recordProcessor.process(record) } throws exception
            every { retryPolicy.shouldRetry(exception) } returns false
            every { recordRepository.save(any()) } returns record

            scheduler.process()

            assertThat(record.retryCount).isEqualTo(1)
            assertThat(record.status).isEqualTo(FAILED)
        }

        @Test
        fun `deletes record by id when deleteCompletedRecords is enabled`() {
            val record = outboxRecord(status = NEW, nextRetryAt = now.minusMinutes(1))
            val propertiesWithDelete =
                properties.copy(processing = properties.processing.copy(deleteCompletedRecords = true))
            val schedulerWithDelete =
                OutboxProcessingScheduler(
                    recordRepository,
                    recordProcessor,
                    partitionCoordinator,
                    instanceRegistry,
                    retryPolicy,
                    propertiesWithDelete,
                    clock,
                )

            every { partitionCoordinator.getAssignedPartitions("test-instance") } returns listOf(1)
            every { recordRepository.findAggregateIdsInPartitions(listOf(1), NEW, 100) } returns listOf("aggregate-1")
            every { recordRepository.findAllIncompleteRecordsByAggregateId("aggregate-1") } returns listOf(record)
            every { recordRepository.deleteById(record.id) } returns Unit

            schedulerWithDelete.process()

            verify(exactly = 1) { recordProcessor.process(record) }
            verify(exactly = 1) { recordRepository.deleteById(record.id) }
            verify(exactly = 0) { recordRepository.save(any()) }
        }
    }

    @Nested
    @DisplayName("Stop On First Failure")
    inner class StopOnFirstFailure {
        @Test
        fun `stop processing on first failure when enabled`() {
            val record1 = outboxRecord(id = "record-1", aggregateId = "aggregate-1", nextRetryAt = now.minusMinutes(1))
            val record2 = outboxRecord(id = "record-2", aggregateId = "aggregate-1", nextRetryAt = now.minusMinutes(1))

            every { partitionCoordinator.getAssignedPartitions("test-instance") } returns listOf(1)
            every { recordRepository.findAggregateIdsInPartitions(listOf(1), NEW, 100) } returns listOf("aggregate-1")
            every { recordRepository.findAllIncompleteRecordsByAggregateId("aggregate-1") } returns
                listOf(record1, record2)
            every { recordProcessor.process(record1) } throws RuntimeException("Processing failed")
            every { recordRepository.save(any()) } returns mockk()

            scheduler.process()

            verify(exactly = 1) { recordProcessor.process(record1) }
            verify(exactly = 0) { recordProcessor.process(record2) }
        }

        @Test
        fun `continue processing on failure when disabled`() {
            val propertiesNonStop =
                properties.copy(
                    processing = OutboxProperties.Processing(stopOnFirstFailure = false),
                )
            val schedulerNonStop =
                OutboxProcessingScheduler(
                    recordRepository,
                    recordProcessor,
                    partitionCoordinator,
                    instanceRegistry,
                    retryPolicy,
                    propertiesNonStop,
                    clock,
                )

            val record1 = outboxRecord(id = "record-1", aggregateId = "aggregate-1", nextRetryAt = now.minusMinutes(1))
            val record2 = outboxRecord(id = "record-2", aggregateId = "aggregate-1", nextRetryAt = now.minusMinutes(1))

            every { partitionCoordinator.getAssignedPartitions("test-instance") } returns listOf(1)
            every { recordRepository.findAggregateIdsInPartitions(listOf(1), NEW, 100) } returns listOf("aggregate-1")
            every { recordRepository.findAllIncompleteRecordsByAggregateId("aggregate-1") } returns
                listOf(record1, record2)
            every { recordProcessor.process(record1) } throws RuntimeException("Processing failed")
            every { recordRepository.save(any()) } returns mockk()

            schedulerNonStop.process()

            verify(exactly = 1) { recordProcessor.process(record1) }
            verify(exactly = 1) { recordProcessor.process(record2) }
        }

        @Test
        fun `continue to next record when first succeeds`() {
            val record1 = outboxRecord(id = "record-1", aggregateId = "aggregate-1", nextRetryAt = now.minusMinutes(1))
            val record2 = outboxRecord(id = "record-2", aggregateId = "aggregate-1", nextRetryAt = now.minusMinutes(1))

            every { partitionCoordinator.getAssignedPartitions("test-instance") } returns listOf(1)
            every { recordRepository.findAggregateIdsInPartitions(listOf(1), NEW, 100) } returns listOf("aggregate-1")
            every { recordRepository.findAllIncompleteRecordsByAggregateId("aggregate-1") } returns
                listOf(record1, record2)
            every { recordRepository.save(any()) } returns mockk()

            scheduler.process()

            verify(exactly = 1) { recordProcessor.process(record1) }
            verify(exactly = 1) { recordProcessor.process(record2) }
        }
    }

    @Nested
    @DisplayName("Error Scenarios")
    inner class ErrorScenarios {
        @Test
        fun `handle partition coordinator exception`() {
            every { partitionCoordinator.getAssignedPartitions("test-instance") } throws
                RuntimeException("Coordinator error")

            scheduler.process()

            verify(exactly = 0) { recordRepository.findAggregateIdsInPartitions(any(), any(), any()) }
        }

        @Test
        fun `handle repository exception during aggregate lookup`() {
            every { partitionCoordinator.getAssignedPartitions("test-instance") } returns listOf(1)
            every { recordRepository.findAggregateIdsInPartitions(listOf(1), NEW, 100) } throws
                RuntimeException("DB error")

            scheduler.process()

            verify(exactly = 0) { recordProcessor.process(any()) }
        }

        @Test
        fun `handle repository exception during record lookup`() {
            every { partitionCoordinator.getAssignedPartitions("test-instance") } returns listOf(1)
            every { recordRepository.findAggregateIdsInPartitions(listOf(1), NEW, 100) } returns listOf("aggregate-1")
            every { recordRepository.findAllIncompleteRecordsByAggregateId("aggregate-1") } throws
                RuntimeException("DB error")

            scheduler.process()

            verify(exactly = 0) { recordProcessor.process(any()) }
        }

        @Test
        fun `handle save exception gracefully`() {
            val record = outboxRecord(nextRetryAt = now.minusMinutes(1))

            every { partitionCoordinator.getAssignedPartitions("test-instance") } returns listOf(1)
            every { recordRepository.findAggregateIdsInPartitions(listOf(1), NEW, 100) } returns listOf("aggregate-1")
            every { recordRepository.findAllIncompleteRecordsByAggregateId("aggregate-1") } returns listOf(record)
            every { recordRepository.save(any()) } throws RuntimeException("Save failed")

            scheduler.process()

            verify(exactly = 1) { recordProcessor.process(record) }
        }
    }
}
