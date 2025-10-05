package io.namastack.springoutbox

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.springoutbox.OutboxProperties.Processing
import io.namastack.springoutbox.OutboxRecordStatus.COMPLETED
import io.namastack.springoutbox.OutboxRecordStatus.FAILED
import io.namastack.springoutbox.OutboxRecordStatus.NEW
import io.namastack.springoutbox.OutboxRecordTestFactory.outboxRecord
import io.namastack.springoutbox.lock.OutboxLockManager
import io.namastack.springoutbox.retry.OutboxRetryPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

class OutboxOrderedProcessingSchedulerTest {
    private val clock: Clock = Clock.systemUTC()
    private val lockManager: OutboxLockManager = mockk()
    private val recordRepository: OutboxRecordRepository = mockk()
    private val processor: OutboxRecordProcessor = mockk()
    private val retryPolicy: OutboxRetryPolicy = mockk()

    @BeforeEach
    fun setUp() {
        every { lockManager.acquire(any()) } returns mockk()
        every { lockManager.release(any()) } returns Unit
        every { lockManager.renew(any()) } returns mockk()

        every { processor.process(any()) } returns Unit

        every { retryPolicy.shouldRetry(any()) } returns true
        every { retryPolicy.nextDelay(any()) } returns Duration.ofSeconds(10)
    }

    @Nested
    inner class OrderedProcessingScheduler {
        private val properties = OutboxProperties(processing = Processing(stopOnFirstFailure = true))
        private val processingScheduler =
            OutboxProcessingScheduler(
                recordRepository = recordRepository,
                recordProcessor = processor,
                lockManager = lockManager,
                retryPolicy = retryPolicy,
                properties = properties,
                clock = clock,
            )

        @Test
        fun `processes single record`() {
            val record = outboxRecord()

            every { recordRepository.findAggregateIdsWithFailedRecords() } returns emptyList()
            every { recordRepository.findAggregateIdsWithPendingRecords(NEW) } returns listOf(record.aggregateId)
            every { recordRepository.findAllIncompleteRecordsByAggregateId(record.aggregateId) } returns listOf(record)
            every { recordRepository.save(record) } returns record

            processingScheduler.process()

            verify(exactly = 1) { processor.process(record) }
            verify(exactly = 1) { lockManager.renew(any()) }
            verify(exactly = 1) { recordRepository.save(record) }

            assertThat(record.completedAt).isNotNull
            assertThat(record.status).isEqualTo(COMPLETED)
        }

        @Test
        fun `does not process record when no lock can be acquired`() {
            val record = outboxRecord()

            every { recordRepository.findAggregateIdsWithFailedRecords() } returns emptyList()
            every { recordRepository.findAggregateIdsWithPendingRecords(NEW) } returns listOf(record.aggregateId)

            every { lockManager.acquire(any()) } returns null

            processingScheduler.process()

            verify(exactly = 0) { processor.process(record) }
        }

        @Test
        fun `does not process record when aggregate has already failed records`() {
            val aggregateId = UUID.randomUUID().toString()

            every { recordRepository.findAggregateIdsWithFailedRecords() } returns listOf(aggregateId)
            every { recordRepository.findAggregateIdsWithPendingRecords(NEW) } returns listOf(aggregateId)

            processingScheduler.process()

            verify(exactly = 0) { processor.process(any()) }
        }

        @Test
        fun `does not process further records of same aggregate when one fails`() {
            val aggregateId = UUID.randomUUID().toString()
            val record1 = outboxRecord(aggregateId = aggregateId)
            val record2 = outboxRecord(aggregateId = aggregateId)

            every { recordRepository.save(any()) } returns mockk()
            every { recordRepository.findAggregateIdsWithFailedRecords() } returns emptyList()
            every { recordRepository.findAggregateIdsWithPendingRecords(NEW) } returns listOf(aggregateId)
            every { recordRepository.findAllIncompleteRecordsByAggregateId(aggregateId) } returns
                listOf(
                    record1,
                    record2,
                )

            every { processor.process(record1) } throws RuntimeException()

            processingScheduler.process()

            verify(exactly = 0) { processor.process(record2) }
        }

        @Test
        fun `does not process record with nextRetryAt in future`() {
            val record = outboxRecord(nextRetryAt = OffsetDateTime.now(clock).plusMinutes(1))

            every { recordRepository.findAggregateIdsWithFailedRecords() } returns emptyList()
            every { recordRepository.findAggregateIdsWithPendingRecords(NEW) } returns listOf(record.aggregateId)
            every { recordRepository.findAllIncompleteRecordsByAggregateId(record.aggregateId) } returns listOf(record)

            processingScheduler.process()

            verify(exactly = 0) { processor.process(any()) }
        }

        @Test
        fun `increments retry count on failure`() {
            val record = outboxRecord()

            every { recordRepository.findAggregateIdsWithFailedRecords() } returns emptyList()
            every { recordRepository.findAggregateIdsWithPendingRecords(NEW) } returns listOf(record.aggregateId)
            every { recordRepository.findAllIncompleteRecordsByAggregateId(record.aggregateId) } returns listOf(record)
            every { recordRepository.save(any()) } returns mockk()

            every { processor.process(record) } throws RuntimeException()

            processingScheduler.process()

            assertThat(record.retryCount).isEqualTo(1)
        }

        @Test
        fun `sets nextRetryAt on failure`() {
            val initialNextRetryAt = OffsetDateTime.now(clock).minusSeconds(30)
            val record = outboxRecord(nextRetryAt = initialNextRetryAt)

            every { recordRepository.findAggregateIdsWithFailedRecords() } returns emptyList()
            every { recordRepository.findAggregateIdsWithPendingRecords(NEW) } returns listOf(record.aggregateId)
            every { recordRepository.findAllIncompleteRecordsByAggregateId(record.aggregateId) } returns listOf(record)
            every { recordRepository.save(any()) } returns mockk()

            every { processor.process(record) } throws RuntimeException()

            processingScheduler.process()

            assertThat(record.nextRetryAt).isNotEqualTo(initialNextRetryAt)
        }

        @Test
        fun `cancels processing if lock renewal fails`() {
            val record = outboxRecord()

            every { recordRepository.findAggregateIdsWithFailedRecords() } returns emptyList()
            every { recordRepository.findAggregateIdsWithPendingRecords(NEW) } returns listOf(record.aggregateId)
            every { recordRepository.findAllIncompleteRecordsByAggregateId(record.aggregateId) } returns listOf(record)

            every { lockManager.renew(any()) } returns null

            processingScheduler.process()

            verify(exactly = 0) { processor.process(any()) }
        }

        @Test
        fun `marks record as failed when retries exhausted`() {
            val record = outboxRecord(retryCount = 2)

            every { recordRepository.findAggregateIdsWithFailedRecords() } returns emptyList()
            every { recordRepository.findAggregateIdsWithPendingRecords(NEW) } returns listOf(record.aggregateId)
            every { recordRepository.findAllIncompleteRecordsByAggregateId(record.aggregateId) } returns listOf(record)
            every { recordRepository.save(any()) } returns mockk()

            every { processor.process(record) } throws RuntimeException()

            processingScheduler.process()

            assertThat(record.retryCount).isEqualTo(3)
            assertThat(record.status).isEqualTo(FAILED)
        }

        @Test
        fun `marks record as failed when retry policy restricts retry for exception`() {
            val record = outboxRecord()

            every { recordRepository.findAggregateIdsWithFailedRecords() } returns emptyList()
            every { recordRepository.findAggregateIdsWithPendingRecords(NEW) } returns listOf(record.aggregateId)
            every { recordRepository.findAllIncompleteRecordsByAggregateId(record.aggregateId) } returns listOf(record)
            every { recordRepository.save(any()) } returns mockk()

            every { processor.process(record) } throws RuntimeException()
            every { retryPolicy.shouldRetry(any()) } returns false

            processingScheduler.process()

            assertThat(record.status).isEqualTo(FAILED)
        }
    }

    @Nested
    inner class UnorderedProcessingScheduler {
        private val properties = OutboxProperties(processing = Processing(stopOnFirstFailure = false))
        private val processingScheduler =
            OutboxProcessingScheduler(
                recordRepository = recordRepository,
                recordProcessor = processor,
                lockManager = lockManager,
                retryPolicy = retryPolicy,
                properties = properties,
                clock = clock,
            )

        @Test
        fun `processes record when aggregate has already failed records`() {
            val aggregateId = UUID.randomUUID().toString()
            val record = outboxRecord(aggregateId = aggregateId)

            every { recordRepository.findAggregateIdsWithFailedRecords() } returns listOf(aggregateId)
            every { recordRepository.findAggregateIdsWithPendingRecords(NEW) } returns listOf(aggregateId)
            every { recordRepository.findAllIncompleteRecordsByAggregateId(aggregateId) } returns listOf(record)
            every { recordRepository.save(any()) } returns mockk()

            processingScheduler.process()

            verify(exactly = 1) { processor.process(record) }
        }

        @Test
        fun `processes further records of same aggregate when one fails`() {
            val aggregateId = UUID.randomUUID().toString()
            val record1 = outboxRecord(aggregateId = aggregateId)
            val record2 = outboxRecord(aggregateId = aggregateId)

            every { recordRepository.save(any()) } returns mockk()
            every { recordRepository.findAggregateIdsWithFailedRecords() } returns emptyList()
            every { recordRepository.findAggregateIdsWithPendingRecords(NEW) } returns listOf(aggregateId)
            every { recordRepository.findAllIncompleteRecordsByAggregateId(aggregateId) } returns
                listOf(
                    record1,
                    record2,
                )

            every { processor.process(record1) } throws RuntimeException()

            processingScheduler.process()

            verify(exactly = 1) { processor.process(record2) }
        }
    }
}
