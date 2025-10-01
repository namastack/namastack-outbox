package com.beisel.springoutbox

import com.beisel.springoutbox.OutboxProperties.Processing
import com.beisel.springoutbox.OutboxRecordStatus.NEW
import com.beisel.springoutbox.lock.OutboxLock
import com.beisel.springoutbox.lock.OutboxLockManager
import com.beisel.springoutbox.lock.OutboxLockRepository
import com.beisel.springoutbox.retry.FixedDelayRetryPolicy
import com.beisel.springoutbox.retry.OutboxRetryPolicy
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

class OutboxUnorderedProcessingSchedulerTest {
    private val clock: Clock = Clock.systemUTC()
    private val properties: OutboxProperties = OutboxProperties(processing = Processing(stopOnFirstFailure = false))
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
        retryCount: Int = 0,
        nextRetryAt: OffsetDateTime = OffsetDateTime.now(clock),
    ): OutboxRecord =
        OutboxRecord.restore(
            id = UUID.randomUUID().toString(),
            aggregateId = aggregateId,
            eventType = eventType,
            payload = "payload",
            createdAt = OffsetDateTime.now(clock),
            status = NEW,
            completedAt = null,
            retryCount = retryCount,
            nextRetryAt = nextRetryAt,
        )
}
