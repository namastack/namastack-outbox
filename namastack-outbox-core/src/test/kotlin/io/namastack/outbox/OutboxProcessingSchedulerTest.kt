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
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

@DisplayName("OutboxProcessingScheduler")
class OutboxProcessingSchedulerTest {
    private val clock = Clock.fixed(Instant.parse("2025-09-25T10:00:00Z"), ZoneOffset.UTC)
    private val now = OffsetDateTime.now(clock)

    private val recordRepository = mockk<OutboxRecordRepository>()
    private val recordProcessor = mockk<OutboxRecordProcessor>()
    private val partitionCoordinator = mockk<PartitionCoordinator>()
    private val retryPolicy = mockk<OutboxRetryPolicy>()

    private val properties =
        OutboxProperties(
            batchSize = 100,
            processing = OutboxProperties.Processing(stopOnFirstFailure = true),
            retry = OutboxProperties.Retry(maxRetries = 3),
        )

    private lateinit var taskExecutor: ThreadPoolTaskExecutor
    private lateinit var scheduler: OutboxProcessingScheduler

    @BeforeEach
    fun setUp() {
        taskExecutor = ThreadPoolTaskExecutor()
        taskExecutor.corePoolSize = 2
        taskExecutor.maxPoolSize = 4
        taskExecutor.setThreadNamePrefix("outbox-proc-")
        taskExecutor.initialize()

        scheduler =
            OutboxProcessingScheduler(
                recordRepository = recordRepository,
                recordProcessor = recordProcessor,
                partitionCoordinator = partitionCoordinator,
                retryPolicy = retryPolicy,
                properties = properties,
                taskExecutor = taskExecutor,
                clock = clock,
            )

        every { recordProcessor.process(any()) } returns Unit
        every { retryPolicy.shouldRetry(any()) } returns true
        every { retryPolicy.nextDelay(any()) } returns Duration.ofSeconds(10)
    }

    @Nested
    @DisplayName("Partition Processing")
    inner class PartitionProcessing {
        @Test
        fun `skip processing when no partitions assigned`() {
            every { partitionCoordinator.getAssignedPartitionNumbers() } returns emptySet()

            scheduler.process()

            verify(exactly = 0) { recordRepository.findAggregateIdsInPartitions(any(), any(), any(), true) }
        }

        @Test
        fun `process records from assigned partitions`() {
            val assignedPartitions = setOf(1, 3, 5)

            every { partitionCoordinator.getAssignedPartitionNumbers() } returns assignedPartitions
            every { recordRepository.findAggregateIdsInPartitions(setOf(1, 3, 5), NEW, 100, true) } returns emptyList()

            scheduler.process()

            verify(exactly = 1) { recordRepository.findAggregateIdsInPartitions(setOf(1, 3, 5), NEW, 100, true) }
        }

        @Test
        fun `respect batch size configuration`() {
            val customProperties = properties.copy(batchSize = 50)
            val customScheduler =
                OutboxProcessingScheduler(
                    recordRepository = recordRepository,
                    recordProcessor = recordProcessor,
                    partitionCoordinator = partitionCoordinator,
                    taskExecutor = taskExecutor,
                    retryPolicy = retryPolicy,
                    properties = customProperties,
                    clock = clock,
                )

            every { partitionCoordinator.getAssignedPartitionNumbers() } returns setOf(1, 2)
            every { recordRepository.findAggregateIdsInPartitions(setOf(1, 2), NEW, 50, true) } returns emptyList()

            customScheduler.process()

            verify(exactly = 1) { recordRepository.findAggregateIdsInPartitions(setOf(1, 2), NEW, 50, true) }
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

            every { partitionCoordinator.getAssignedPartitionNumbers() } returns setOf(1)
            every {
                recordRepository.findAggregateIdsInPartitions(
                    setOf(1),
                    NEW,
                    100,
                    true,
                )
            } returns listOf("aggregate-1")
            every { recordRepository.findIncompleteRecordsByAggregateId("aggregate-1") } returns listOf(record)
            every { recordRepository.save(any()) } returns record

            scheduler.process()

            await()
                .atMost(2, TimeUnit.SECONDS)
                .untilAsserted {
                    verify(exactly = 1) { recordProcessor.process(record) }
                    verify(exactly = 1) { recordRepository.save(record) }
                    assertThat(record.status).isEqualTo(COMPLETED)
                    assertThat(record.completedAt).isNotNull()
                }
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

            every { partitionCoordinator.getAssignedPartitionNumbers() } returns setOf(1)
            every {
                recordRepository.findAggregateIdsInPartitions(
                    setOf(1),
                    NEW,
                    100,
                    true,
                )
            } returns listOf("aggregate-1")
            every { recordRepository.findIncompleteRecordsByAggregateId("aggregate-1") } returns
                listOf(record1, record2)
            every { recordRepository.save(any()) } returns mockk()

            scheduler.process()

            await()
                .atMost(2, TimeUnit.SECONDS)
                .untilAsserted {
                    verify(exactly = 1) { recordProcessor.process(record1) }
                    verify(exactly = 1) { recordProcessor.process(record2) }
                }
        }

        @Test
        fun `skip records not ready for retry`() {
            val futureRecord =
                outboxRecord(
                    aggregateId = "aggregate-1",
                    nextRetryAt = now.plusMinutes(5),
                )

            every { partitionCoordinator.getAssignedPartitionNumbers() } returns setOf(1)
            every {
                recordRepository.findAggregateIdsInPartitions(
                    setOf(1),
                    NEW,
                    100,
                    true,
                )
            } returns listOf("aggregate-1")
            every { recordRepository.findIncompleteRecordsByAggregateId("aggregate-1") } returns listOf(futureRecord)

            scheduler.process()

            await().during(1, TimeUnit.SECONDS).untilAsserted {
                verify(exactly = 0) { recordProcessor.process(any()) }
            }
        }

        @Test
        fun `handle empty aggregate`() {
            every { partitionCoordinator.getAssignedPartitionNumbers() } returns setOf(1)
            every {
                recordRepository.findAggregateIdsInPartitions(
                    setOf(1),
                    NEW,
                    100,
                    true,
                )
            } returns listOf("aggregate-1")
            every { recordRepository.findIncompleteRecordsByAggregateId("aggregate-1") } returns emptyList()

            scheduler.process()

            await().during(1, TimeUnit.SECONDS).untilAsserted {
                verify(exactly = 0) { recordProcessor.process(any()) }
            }
        }
    }

    @Nested
    @DisplayName("Record Processing")
    inner class RecordProcessing {
        @Test
        fun `mark record as completed on success`() {
            val record = outboxRecord(status = NEW, nextRetryAt = now.minusMinutes(1))

            every { partitionCoordinator.getAssignedPartitionNumbers() } returns setOf(1)
            every {
                recordRepository.findAggregateIdsInPartitions(
                    setOf(1),
                    NEW,
                    100,
                    true,
                )
            } returns listOf("aggregate-1")
            every { recordRepository.findIncompleteRecordsByAggregateId("aggregate-1") } returns listOf(record)
            every { recordRepository.save(any()) } returns record

            scheduler.process()

            await()
                .atMost(2, TimeUnit.SECONDS)
                .untilAsserted {
                    assertThat(record.status).isEqualTo(COMPLETED)
                    assertThat(record.completedAt).isEqualTo(now)
                }
        }

        @Test
        fun `increment retry count on failure`() {
            val record = outboxRecord(status = NEW, retryCount = 0, nextRetryAt = now.minusMinutes(1))

            every { partitionCoordinator.getAssignedPartitionNumbers() } returns setOf(1)
            every {
                recordRepository.findAggregateIdsInPartitions(
                    setOf(1),
                    NEW,
                    100,
                    true,
                )
            } returns listOf("aggregate-1")
            every { recordRepository.findIncompleteRecordsByAggregateId("aggregate-1") } returns listOf(record)
            every { recordProcessor.process(record) } throws RuntimeException("Processing failed")
            every { recordRepository.save(any()) } returns record

            scheduler.process()

            await()
                .atMost(2, TimeUnit.SECONDS)
                .untilAsserted {
                    assertThat(record.retryCount).isEqualTo(1)
                    assertThat(record.nextRetryAt).isEqualTo(now.plus(Duration.ofSeconds(10)))
                    assertThat(record.status).isEqualTo(NEW)
                }
        }

        @Test
        fun `mark as failed after max retries`() {
            val record = outboxRecord(status = NEW, retryCount = 3, nextRetryAt = now.minusMinutes(1))

            every { partitionCoordinator.getAssignedPartitionNumbers() } returns setOf(1)
            every {
                recordRepository.findAggregateIdsInPartitions(
                    setOf(1),
                    NEW,
                    100,
                    true,
                )
            } returns listOf("aggregate-1")
            every { recordRepository.findIncompleteRecordsByAggregateId("aggregate-1") } returns listOf(record)
            every { recordProcessor.process(record) } throws RuntimeException("Processing failed")
            every { recordRepository.save(any()) } returns record

            scheduler.process()

            await()
                .atMost(2, TimeUnit.SECONDS)
                .untilAsserted {
                    assertThat(record.retryCount).isEqualTo(3)
                    assertThat(record.status).isEqualTo(FAILED)
                }
        }

        @Test
        fun `mark as failed when retry policy rejects`() {
            val record = outboxRecord(status = NEW, retryCount = 0, nextRetryAt = now.minusMinutes(1))
            val exception = RuntimeException("Non-retryable error")

            every { partitionCoordinator.getAssignedPartitionNumbers() } returns setOf(1)
            every {
                recordRepository.findAggregateIdsInPartitions(
                    setOf(1),
                    NEW,
                    100,
                    true,
                )
            } returns listOf("aggregate-1")
            every { recordRepository.findIncompleteRecordsByAggregateId("aggregate-1") } returns listOf(record)
            every { recordProcessor.process(record) } throws exception
            every { retryPolicy.shouldRetry(exception) } returns false
            every { recordRepository.save(any()) } returns record

            scheduler.process()

            await()
                .atMost(2, TimeUnit.SECONDS)
                .untilAsserted {
                    assertThat(record.retryCount).isEqualTo(0)
                    assertThat(record.status).isEqualTo(FAILED)
                }
        }

        @Test
        fun `deletes record by id when deleteCompletedRecords is enabled`() {
            val record = outboxRecord(status = NEW, nextRetryAt = now.minusMinutes(1))
            val propertiesWithDelete =
                properties.copy(processing = properties.processing.copy(deleteCompletedRecords = true))
            val schedulerWithDelete =
                OutboxProcessingScheduler(
                    recordRepository = recordRepository,
                    recordProcessor = recordProcessor,
                    partitionCoordinator = partitionCoordinator,
                    taskExecutor = taskExecutor,
                    retryPolicy = retryPolicy,
                    properties = propertiesWithDelete,
                    clock = clock,
                )

            every { partitionCoordinator.getAssignedPartitionNumbers() } returns setOf(1)
            every {
                recordRepository.findAggregateIdsInPartitions(
                    setOf(1),
                    NEW,
                    100,
                    true,
                )
            } returns listOf("aggregate-1")
            every { recordRepository.findIncompleteRecordsByAggregateId("aggregate-1") } returns listOf(record)
            every { recordRepository.deleteById(record.id) } returns Unit

            schedulerWithDelete.process()

            await()
                .atMost(2, TimeUnit.SECONDS)
                .untilAsserted {
                    verify(exactly = 1) { recordProcessor.process(record) }
                    verify(exactly = 1) { recordRepository.deleteById(record.id) }
                    verify(exactly = 0) { recordRepository.save(any()) }
                }
        }
    }

    @Nested
    @DisplayName("Stop On First Failure")
    inner class StopOnFirstFailure {
        @Test
        fun `stop processing on first failure when enabled`() {
            val record1 = outboxRecord(id = "record-1", aggregateId = "aggregate-1", nextRetryAt = now.minusMinutes(1))
            val record2 = outboxRecord(id = "record-2", aggregateId = "aggregate-1", nextRetryAt = now.minusMinutes(1))

            every { partitionCoordinator.getAssignedPartitionNumbers() } returns setOf(1)
            every {
                recordRepository.findAggregateIdsInPartitions(
                    setOf(1),
                    NEW,
                    100,
                    true,
                )
            } returns listOf("aggregate-1")
            every { recordRepository.findIncompleteRecordsByAggregateId("aggregate-1") } returns
                listOf(record1, record2)
            every { recordProcessor.process(record1) } throws RuntimeException("Processing failed")
            every { recordRepository.save(any()) } returns mockk()

            scheduler.process()

            await()
                .atMost(2, TimeUnit.SECONDS)
                .untilAsserted {
                    verify(exactly = 1) { recordProcessor.process(record1) }
                }

            await().during(1, TimeUnit.SECONDS).untilAsserted {
                verify(exactly = 0) { recordProcessor.process(record2) }
            }
        }

        @Test
        fun `continue processing on failure when disabled`() {
            val propertiesNonStop =
                properties.copy(
                    processing = OutboxProperties.Processing(stopOnFirstFailure = false),
                )
            val schedulerNonStop =
                OutboxProcessingScheduler(
                    recordRepository = recordRepository,
                    recordProcessor = recordProcessor,
                    partitionCoordinator = partitionCoordinator,
                    taskExecutor = taskExecutor,
                    retryPolicy = retryPolicy,
                    properties = propertiesNonStop,
                    clock = clock,
                )

            val record1 = outboxRecord(id = "record-1", aggregateId = "aggregate-1", nextRetryAt = now.minusMinutes(1))
            val record2 = outboxRecord(id = "record-2", aggregateId = "aggregate-1", nextRetryAt = now.minusMinutes(1))

            every { partitionCoordinator.getAssignedPartitionNumbers() } returns setOf(1)
            every {
                recordRepository.findAggregateIdsInPartitions(
                    setOf(1),
                    NEW,
                    100,
                    false,
                )
            } returns listOf("aggregate-1")
            every { recordRepository.findIncompleteRecordsByAggregateId("aggregate-1") } returns
                listOf(record1, record2)
            every { recordProcessor.process(record1) } throws RuntimeException("Processing failed")
            every { recordRepository.save(any()) } returns mockk()

            schedulerNonStop.process()

            await()
                .atMost(2, TimeUnit.SECONDS)
                .untilAsserted {
                    verify(exactly = 1) { recordProcessor.process(record1) }
                    verify(exactly = 1) { recordProcessor.process(record2) }
                }
        }

        @Test
        fun `continue to next record when first succeeds`() {
            val record1 = outboxRecord(id = "record-1", aggregateId = "aggregate-1", nextRetryAt = now.minusMinutes(1))
            val record2 = outboxRecord(id = "record-2", aggregateId = "aggregate-1", nextRetryAt = now.minusMinutes(1))

            every { partitionCoordinator.getAssignedPartitionNumbers() } returns setOf(1)
            every {
                recordRepository.findAggregateIdsInPartitions(
                    setOf(1),
                    NEW,
                    100,
                    true,
                )
            } returns listOf("aggregate-1")
            every { recordRepository.findIncompleteRecordsByAggregateId("aggregate-1") } returns
                listOf(record1, record2)
            every { recordRepository.save(any()) } returns mockk()

            scheduler.process()

            await()
                .atMost(2, TimeUnit.SECONDS)
                .untilAsserted {
                    verify(exactly = 1) { recordProcessor.process(record1) }
                    verify(exactly = 1) { recordProcessor.process(record2) }
                }
        }
    }

    @Nested
    @DisplayName("Error Scenarios")
    inner class ErrorScenarios {
        @Test
        fun `handle partition coordinator exception`() {
            every { partitionCoordinator.getAssignedPartitionNumbers() } throws
                RuntimeException("Coordinator error")

            scheduler.process()

            verify(exactly = 0) { recordRepository.findAggregateIdsInPartitions(any(), any(), any(), true) }
        }

        @Test
        fun `handle repository exception during aggregate lookup`() {
            every { partitionCoordinator.getAssignedPartitionNumbers() } returns setOf(1)
            every { recordRepository.findAggregateIdsInPartitions(setOf(1), NEW, 100, true) } throws
                RuntimeException("DB error")

            scheduler.process()

            await().during(1, TimeUnit.SECONDS).untilAsserted {
                verify(exactly = 0) { recordProcessor.process(any()) }
            }
        }

        @Test
        fun `handle repository exception during record lookup`() {
            every { partitionCoordinator.getAssignedPartitionNumbers() } returns setOf(1)
            every {
                recordRepository.findAggregateIdsInPartitions(
                    setOf(1),
                    NEW,
                    100,
                    true,
                )
            } returns listOf("aggregate-1")
            every { recordRepository.findIncompleteRecordsByAggregateId("aggregate-1") } throws
                RuntimeException("DB error")

            scheduler.process()

            await().during(1, TimeUnit.SECONDS).untilAsserted {
                verify(exactly = 0) { recordProcessor.process(any()) }
            }
        }

        @Test
        fun `handle save exception gracefully`() {
            val record = outboxRecord(nextRetryAt = now.minusMinutes(1))

            every { partitionCoordinator.getAssignedPartitionNumbers() } returns setOf(1)
            every {
                recordRepository.findAggregateIdsInPartitions(
                    setOf(1),
                    NEW,
                    100,
                    true,
                )
            } returns listOf("aggregate-1")
            every { recordRepository.findIncompleteRecordsByAggregateId("aggregate-1") } returns listOf(record)
            every { recordRepository.save(any()) } throws RuntimeException("Save failed")

            scheduler.process()

            await()
                .atMost(2, TimeUnit.SECONDS)
                .untilAsserted {
                    verify(exactly = 1) { recordProcessor.process(record) }
                }
        }
    }
}
