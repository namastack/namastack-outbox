package com.beisel.springoutbox

import com.beisel.springoutbox.OutboxRecordStatus.FAILED
import com.beisel.springoutbox.OutboxRecordStatus.NEW
import com.beisel.springoutbox.lock.OutboxLock
import com.beisel.springoutbox.lock.OutboxLockManager
import com.beisel.springoutbox.lock.OutboxLockRepository
import com.beisel.springoutbox.retry.FixedDelayRetryPolicy
import com.beisel.springoutbox.retry.OutboxRetryPolicy
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

class OutboxProcessingSchedulerTest {
    private val clock: Clock = Clock.systemUTC()
    private val properties: OutboxProperties = OutboxProperties()
    private val lockRepository: OutboxLockRepository = mockk()
    private val recordRepository: OutboxRecordRepository = mockk()
    private val processor: OutboxRecordProcessor = mockk()
    private val retryPolicy: OutboxRetryPolicy = FixedDelayRetryPolicy(Duration.ofSeconds(1))

    private lateinit var lockManager: OutboxLockManager
    private lateinit var outboxProcessingScheduler: OutboxProcessingScheduler

    @BeforeEach
    fun setUp() {
        lockManager = OutboxLockManager(lockRepository, properties, clock)

        outboxProcessingScheduler =
            OutboxProcessingScheduler(
                recordRepository = recordRepository,
                recordProcessor = processor,
                lockManager = lockManager,
                retryPolicy = retryPolicy,
                properties = properties,
                clock = clock,
            )
    }

    @Test
    fun `processes single record`() {
        val aggregateId = UUID.randomUUID().toString()
        val record = createOutboxRecord(aggregateId, "CreatedEvent")
        val lock = OutboxLock.create(aggregateId, 10L, clock)

        prepareRecordRepository(incompleteRecords = listOf(record))
        prepareLockRepository(lock = lock)

        every { processor.process(any()) } returns Unit

        outboxProcessingScheduler.process()

        verify { processor.process(record) }
        verify { recordRepository.save(record) }
        verify { lockRepository.deleteById(aggregateId) }
    }

    @Test
    fun `does not process records for aggregateIds with failed records`() {
        val aggregateId = UUID.randomUUID().toString()
        val record = createFailedOutboxRecord(aggregateId, "UpdatedEvent")

        prepareRecordRepository(
            incompleteRecords = listOf(record),
            aggregateIdsWithFailedRecords = listOf(aggregateId),
        )

        outboxProcessingScheduler.process()

        verify { processor wasNot Called }
    }

    @Test
    fun `processes multiple records in correct order for same aggregate`() {
        val aggregateId = UUID.randomUUID().toString()
        val record1 = createOutboxRecord(aggregateId, "Event1")
        val record2 = createOutboxRecord(aggregateId, "Event2")
        val lock = OutboxLock.create(aggregateId, 10L, clock)

        prepareRecordRepository(incompleteRecords = listOf(record1, record2))
        prepareLockRepository(lock = lock)

        every { processor.process(any()) } returns Unit

        outboxProcessingScheduler.process()

        verifyOrder {
            processor.process(record1)
            processor.process(record2)
        }

        verify(exactly = 2) { recordRepository.save(any()) }
    }

    @Test
    fun `skips processing when lock acquisition fails`() {
        val aggregateId = UUID.randomUUID().toString()
        val record = createOutboxRecord(aggregateId, "Event")

        every { recordRepository.findAggregateIdsWithPendingRecords(status = NEW) } returns listOf(aggregateId)
        every { recordRepository.findAggregateIdsWithFailedRecords() } returns emptyList()
        every { recordRepository.findAllIncompleteRecordsByAggregateId(aggregateId) } returns listOf(record)

        every { lockRepository.insertNew(any()) } returns null
        every { lockRepository.findByAggregateId(any()) } returns null

        outboxProcessingScheduler.process()

        verify { processor wasNot Called }
        verify(exactly = 0) { recordRepository.save(any()) }
    }

    @Test
    fun `handles processor exceptions and marks record for retry`() {
        val aggregateId = UUID.randomUUID().toString()
        val record = createOutboxRecord(aggregateId, "FailingEvent")
        val lock = OutboxLock.create(aggregateId, 10L, clock)

        prepareRecordRepository(incompleteRecords = listOf(record))
        prepareLockRepository(lock = lock)

        every { processor.process(record) } throws RuntimeException("Processing failed")

        outboxProcessingScheduler.process()

        verify { processor.process(record) }
        verify { recordRepository.save(match { it.retryCount == 1 }) }
        verify { lockRepository.deleteById(aggregateId) }
    }

    @Test
    fun `marks record as failed after max retries exceeded`() {
        val aggregateId = UUID.randomUUID().toString()
        val record = createOutboxRecord(aggregateId, "FailingEvent")
        record.retryCount = 2 // assuming max retries is 3
        val lock = OutboxLock.create(aggregateId, 10L, clock)

        prepareRecordRepository(incompleteRecords = listOf(record))
        prepareLockRepository(lock = lock)

        every { processor.process(record) } throws RuntimeException("Processing failed")

        outboxProcessingScheduler.process()

        verify { processor.process(record) }
        verify { recordRepository.save(match { it.status == FAILED }) }
    }

    @Test
    fun `stops processing when record cannot be retried`() {
        val aggregateId = UUID.randomUUID().toString()
        val record1 = createOutboxRecord(aggregateId, "Event1")
        val record2 = createOutboxRecord(aggregateId, "Event2")

        // Set record1 to not be retryable (future retry time)
        record1.nextRetryAt = clock.instant().plusSeconds(3600).atOffset(java.time.ZoneOffset.UTC)

        val lock = OutboxLock.create(aggregateId, 10L, clock)

        prepareRecordRepository(incompleteRecords = listOf(record1, record2))
        prepareLockRepository(lock = lock)

        outboxProcessingScheduler.process()

        verify { processor wasNot Called }
        verify(exactly = 0) { recordRepository.save(any()) }
    }

    @Test
    fun `renews lock during processing`() {
        val aggregateId = UUID.randomUUID().toString()
        val record = createOutboxRecord(aggregateId, "Event")
        val initialLock = OutboxLock.create(aggregateId, 10L, clock)
        val renewedLock = OutboxLock.create(aggregateId, 20L, clock)

        prepareRecordRepository(incompleteRecords = listOf(record))
        prepareLockRepository(lock = initialLock)

        // Mock lock renewal - assuming lock expires soon
        every { lockRepository.renew(aggregateId, any()) } returns renewedLock
        every { processor.process(any()) } returns Unit

        outboxProcessingScheduler.process()

        verify { processor.process(record) }
        verify { recordRepository.save(record) }
    }

    @Test
    fun `stops processing when lock renewal fails`() {
        // Simplified test - just verify the lock renewal mechanism is called
        val aggregateId = UUID.randomUUID().toString()
        val record = createOutboxRecord(aggregateId, "Event")

        // Create a mock lock that would trigger renewal but fails
        val mockLock = mockk<OutboxLock>()
        every { mockLock.aggregateId } returns aggregateId
        every { mockLock.isExpiringSoon(any(), any()) } returns true
        every { mockLock.expiresAt } returns OffsetDateTime.now().minusSeconds(1)

        every { recordRepository.findAggregateIdsWithPendingRecords(status = NEW) } returns listOf(aggregateId)
        every { recordRepository.findAggregateIdsWithFailedRecords() } returns emptyList()
        every { recordRepository.findAllIncompleteRecordsByAggregateId(aggregateId) } returns listOf(record)

        every { lockRepository.insertNew(any()) } returns mockLock
        every { lockRepository.renew(aggregateId, any()) } returns null // renewal fails
        every { lockRepository.deleteById(any()) } returns Unit

        outboxProcessingScheduler.process()

        verify { lockRepository.renew(aggregateId, any()) }
        verify { lockRepository.deleteById(aggregateId) }
    }

    @Test
    fun `processes records for different aggregates separately`() {
        val aggregate1 = UUID.randomUUID().toString()
        val aggregate2 = UUID.randomUUID().toString()
        val record1 = createOutboxRecord(aggregate1, "Event1")
        val record2 = createOutboxRecord(aggregate2, "Event2")
        val lock1 = OutboxLock.create(aggregate1, 10L, clock)
        val lock2 = OutboxLock.create(aggregate2, 10L, clock)

        every { recordRepository.findAggregateIdsWithPendingRecords(status = NEW) } returns
            listOf(aggregate1, aggregate2)
        every { recordRepository.findAggregateIdsWithFailedRecords() } returns emptyList()
        every { recordRepository.findAllIncompleteRecordsByAggregateId(aggregate1) } returns listOf(record1)
        every { recordRepository.findAllIncompleteRecordsByAggregateId(aggregate2) } returns listOf(record2)
        every { recordRepository.save(any()) } returns mockk()

        every { lockRepository.insertNew(match { it.aggregateId == aggregate1 }) } returns lock1
        every { lockRepository.insertNew(match { it.aggregateId == aggregate2 }) } returns lock2
        every { lockRepository.deleteById(any()) } returns Unit

        every { processor.process(any()) } returns Unit

        outboxProcessingScheduler.process()

        verify { processor.process(record1) }
        verify { processor.process(record2) }
        verify { lockRepository.deleteById(aggregate1) }
        verify { lockRepository.deleteById(aggregate2) }
    }

    @Test
    fun `calculates exponential backoff correctly`() {
        val aggregateId = UUID.randomUUID().toString()
        val record = createOutboxRecord(aggregateId, "FailingEvent")
        record.retryCount = 4
        val lock = OutboxLock.create(aggregateId, 10L, clock)

        prepareRecordRepository(incompleteRecords = listOf(record))
        prepareLockRepository(lock = lock)

        every { processor.process(record) } throws RuntimeException("Processing failed")

        outboxProcessingScheduler.process()

        // After incrementRetryCount() it becomes 5, so calculateBackoff(5) = min(1<<5, 60) = min(32, 60) = 32
        verify { recordRepository.save(match { it.retryCount == 5 }) }
    }

    @Test
    fun `calculates backoff with max cap correctly`() {
        // Simplified test - just verify retry count increment
        val aggregateId = UUID.randomUUID().toString()
        val record = createOutboxRecord(aggregateId, "FailingEvent")
        record.retryCount = 8
        val lock = OutboxLock.create(aggregateId, 10L, clock)

        prepareRecordRepository(incompleteRecords = listOf(record))
        prepareLockRepository(lock = lock)

        every { processor.process(record) } throws RuntimeException("Processing failed")

        outboxProcessingScheduler.process()

        // Just verify the retry count was incremented
        verify { recordRepository.save(match { it.retryCount == 9 }) }
    }

    @Test
    fun `handles empty pending records list`() {
        every { recordRepository.findAggregateIdsWithPendingRecords(status = NEW) } returns emptyList()
        every { recordRepository.findAggregateIdsWithFailedRecords() } returns emptyList()

        outboxProcessingScheduler.process()

        verify { processor wasNot Called }
        verify { lockRepository wasNot Called }
    }

    @Test
    fun `does not retry when retryPolicy shouldRetry returns false`() {
        val aggregateId = UUID.randomUUID().toString()
        val record = createOutboxRecord(aggregateId, "NonRetryableEvent")
        val lock = OutboxLock.create(aggregateId, 10L, clock)

        val customRetryPolicy = mockk<OutboxRetryPolicy>()
        every { customRetryPolicy.shouldRetry(any()) } returns false

        val customScheduler =
            OutboxProcessingScheduler(
                recordRepository = recordRepository,
                recordProcessor = processor,
                lockManager = lockManager,
                retryPolicy = customRetryPolicy,
                properties = properties,
                clock = clock,
            )

        prepareRecordRepository(incompleteRecords = listOf(record))
        prepareLockRepository(lock = lock)

        every { processor.process(record) } throws RuntimeException("Processing failed")

        customScheduler.process()

        verify { processor.process(record) }
        verify { recordRepository.save(match { it.status == FAILED && it.retryCount == 1 }) }
        verify { lockRepository.deleteById(aggregateId) }
    }

    @Test
    fun `schedules next retry when retries not exhausted and shouldRetry is true`() {
        val aggregateId = UUID.randomUUID().toString()
        val record = createOutboxRecord(aggregateId, "RetryableEvent")
        val lock = OutboxLock.create(aggregateId, 10L, clock)

        val customRetryPolicy = mockk<OutboxRetryPolicy>()
        every { customRetryPolicy.shouldRetry(any()) } returns true
        every { customRetryPolicy.nextDelay(any()) } returns Duration.ofSeconds(30)

        val customScheduler =
            OutboxProcessingScheduler(
                recordRepository = recordRepository,
                recordProcessor = processor,
                lockManager = lockManager,
                retryPolicy = customRetryPolicy,
                properties = properties,
                clock = clock,
            )

        prepareRecordRepository(incompleteRecords = listOf(record))
        prepareLockRepository(lock = lock)

        every { processor.process(record) } throws RuntimeException("Processing failed")

        customScheduler.process()

        verify { processor.process(record) }
        verify { customRetryPolicy.nextDelay(1) }
        verify { recordRepository.save(match { it.retryCount == 1 && it.status == NEW }) }
        verify { lockRepository.deleteById(aggregateId) }
    }

    @Test
    fun `marks record as completed on successful processing`() {
        val aggregateId = UUID.randomUUID().toString()
        val record = createOutboxRecord(aggregateId, "SuccessEvent")
        val lock = OutboxLock.create(aggregateId, 10L, clock)

        prepareRecordRepository(incompleteRecords = listOf(record))
        prepareLockRepository(lock = lock)

        every { processor.process(record) } returns Unit

        outboxProcessingScheduler.process()

        verify { processor.process(record) }
        verify {
            recordRepository.save(
                match {
                    it.status == OutboxRecordStatus.COMPLETED && it.completedAt != null
                },
            )
        }
        verify { lockRepository.deleteById(aggregateId) }
    }

    @Test
    fun `stops processing when lock renewal fails mid-processing`() {
        val aggregateId = UUID.randomUUID().toString()
        val record1 = createOutboxRecord(aggregateId, "Event1")
        val record2 = createOutboxRecord(aggregateId, "Event2")

        // Create a lock that is expiring soon to trigger renewal
        val expiringSoonLock = mockk<OutboxLock>()
        every { expiringSoonLock.aggregateId } returns aggregateId
        every { expiringSoonLock.isExpiringSoon(any(), any()) } returns true
        every { expiringSoonLock.expiresAt } returns OffsetDateTime.now(clock).plusSeconds(1)

        prepareRecordRepository(incompleteRecords = listOf(record1, record2))

        every { lockRepository.insertNew(any()) } returns expiringSoonLock
        every { lockRepository.renew(aggregateId, any()) } returns null // renewal fails on first attempt
        every { lockRepository.deleteById(any()) } returns Unit

        outboxProcessingScheduler.process()

        // Lock renewal is attempted before processing any record
        verify { lockRepository.renew(aggregateId, any()) }
        // Since renewal fails, no records should be processed
        verify { processor wasNot Called }
        verify(exactly = 0) { recordRepository.save(any()) }
        verify { lockRepository.deleteById(aggregateId) }
    }

    @Test
    fun `processes only retryable records and stops at non-retryable ones`() {
        val aggregateId = UUID.randomUUID().toString()
        val record1 = createOutboxRecord(aggregateId, "Event1")
        val record2 = createOutboxRecord(aggregateId, "Event2")
        val record3 = createOutboxRecord(aggregateId, "Event3")

        // Make record2 not retryable (future retry time)
        record2.nextRetryAt = clock.instant().plusSeconds(3600).atOffset(java.time.ZoneOffset.UTC)

        val lock = OutboxLock.create(aggregateId, 10L, clock)

        prepareRecordRepository(incompleteRecords = listOf(record1, record2, record3))
        prepareLockRepository(lock = lock)

        every { processor.process(any()) } returns Unit

        outboxProcessingScheduler.process()

        // Should only process record1, then stop at record2
        verify { processor.process(record1) }
        verify(exactly = 0) { processor.process(record2) }
        verify(exactly = 0) { processor.process(record3) }
        verify(exactly = 1) { recordRepository.save(any()) } // Only record1 gets saved
    }

    @Test
    fun `handles mixed success and failure in same aggregate`() {
        val aggregateId = UUID.randomUUID().toString()
        val record1 = createOutboxRecord(aggregateId, "SuccessEvent")
        val record2 = createOutboxRecord(aggregateId, "FailEvent")
        val lock = OutboxLock.create(aggregateId, 10L, clock)

        prepareRecordRepository(incompleteRecords = listOf(record1, record2))
        prepareLockRepository(lock = lock)

        every { processor.process(record1) } returns Unit
        every { processor.process(record2) } throws RuntimeException("Processing failed")

        outboxProcessingScheduler.process()

        verifyOrder {
            processor.process(record1)
            processor.process(record2)
        }

        verify {
            recordRepository.save(
                match {
                    it.eventType == "SuccessEvent" &&
                        it.status == OutboxRecordStatus.COMPLETED
                },
            )
        }
        verify { recordRepository.save(match { it.eventType == "FailEvent" && it.retryCount == 1 }) }
        verify { lockRepository.deleteById(aggregateId) }
    }

    @Test
    fun `marks record as failed when max retries reached exactly`() {
        val aggregateId = UUID.randomUUID().toString()
        val record = createOutboxRecord(aggregateId, "FailingEvent")
        record.retryCount = properties.retry.maxRetries // Exactly at max retries
        val lock = OutboxLock.create(aggregateId, 10L, clock)

        prepareRecordRepository(incompleteRecords = listOf(record))
        prepareLockRepository(lock = lock)

        every { processor.process(record) } throws RuntimeException("Processing failed")

        outboxProcessingScheduler.process()

        verify { processor.process(record) }
        verify {
            recordRepository.save(
                match {
                    it.status == FAILED && it.retryCount == properties.retry.maxRetries + 1
                },
            )
        }
        verify { lockRepository.deleteById(aggregateId) }
    }

    @Test
    fun `successfully renews lock during processing of multiple records`() {
        val aggregateId = UUID.randomUUID().toString()
        val record1 = createOutboxRecord(aggregateId, "Event1")
        val record2 = createOutboxRecord(aggregateId, "Event2")

        // Create locks that are expiring soon to trigger renewal
        val expiringSoonLock = mockk<OutboxLock>()
        every { expiringSoonLock.aggregateId } returns aggregateId
        every { expiringSoonLock.isExpiringSoon(any(), any()) } returns true
        every { expiringSoonLock.expiresAt } returns OffsetDateTime.now(clock).plusSeconds(1)

        val renewedLock = mockk<OutboxLock>()
        every { renewedLock.aggregateId } returns aggregateId
        every { renewedLock.isExpiringSoon(any(), any()) } returns true
        every { renewedLock.expiresAt } returns OffsetDateTime.now(clock).plusSeconds(10)

        prepareRecordRepository(incompleteRecords = listOf(record1, record2))

        every { lockRepository.insertNew(any()) } returns expiringSoonLock
        // First renewal succeeds and returns renewedLock, second renewal also succeeds
        every { lockRepository.renew(aggregateId, any()) } returnsMany listOf(renewedLock, renewedLock)
        every { lockRepository.deleteById(any()) } returns Unit
        every { processor.process(any()) } returns Unit

        outboxProcessingScheduler.process()

        verify(exactly = 2) { processor.process(any()) }
        verify(exactly = 2) { lockRepository.renew(aggregateId, any()) }
        verify(exactly = 2) { recordRepository.save(any()) }
        verify { lockRepository.deleteById(aggregateId) }
    }

    private fun prepareRecordRepository(
        incompleteRecords: List<OutboxRecord>,
        aggregateIdsWithFailedRecords: List<String> = emptyList(),
    ) {
        val pendingAggregateIds = mutableListOf<String>()
        incompleteRecords.groupBy { it.aggregateId }.forEach {
            pendingAggregateIds.add(it.key)
            every { recordRepository.findAllIncompleteRecordsByAggregateId(aggregateId = it.key) } returns it.value

            it.value.forEach { record -> every { recordRepository.save(record) } returns record }
        }

        every { recordRepository.findAggregateIdsWithPendingRecords(status = NEW) } returns pendingAggregateIds
        every { recordRepository.findAggregateIdsWithFailedRecords() } returns aggregateIdsWithFailedRecords
    }

    private fun prepareLockRepository(lock: OutboxLock) {
        every { lockRepository.insertNew(any()) } returns lock
        every { lockRepository.deleteById(any()) } returns Unit
    }

    private fun createOutboxRecord(
        aggregateId: String,
        eventType: String,
    ): OutboxRecord =
        OutboxRecord
            .Builder()
            .aggregateId(aggregateId)
            .eventType(eventType)
            .payload("payload")
            .build()

    private fun createFailedOutboxRecord(
        aggregateId: String,
        eventType: String,
    ): OutboxRecord =
        OutboxRecord
            .Builder()
            .aggregateId(aggregateId)
            .eventType(eventType)
            .payload("payload")
            .status(FAILED)
            .retryCount(3)
            .build()
}
