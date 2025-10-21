package io.namastack.outbox

import io.namastack.outbox.OutboxRecordStatus.NEW
import io.namastack.outbox.lock.OutboxLock
import io.namastack.outbox.lock.OutboxLockManager
import io.namastack.outbox.retry.OutboxRetryPolicy
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import java.time.Clock

/**
 * Scheduler responsible for processing outbox records at regular intervals.
 *
 * This class implements the core scheduling and processing logic for the outbox pattern.
 * It acquires locks on aggregates, processes pending records, and handles failures and retries.
 *
 * @param recordRepository Repository for accessing outbox records
 * @param recordProcessor Processor for handling individual records
 * @param lockManager Manager for acquiring and releasing locks
 * @param retryPolicy Policy for determining retry behavior
 * @param properties Configuration properties
 * @param clock Clock for time-based operations
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
class OutboxProcessingScheduler(
    private val recordRepository: OutboxRecordRepository,
    private val recordProcessor: OutboxRecordProcessor,
    private val lockManager: OutboxLockManager,
    private val retryPolicy: OutboxRetryPolicy,
    private val properties: OutboxProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(OutboxProcessingScheduler::class.java)

    /**
     * Main processing method that runs on a scheduled interval.
     *
     * Finds aggregates with pending records, acquires locks, and processes records
     * for each aggregate in a thread-safe manner.
     */
    @Scheduled(fixedDelayString = $$"${outbox.poll-interval}")
    fun process() {
        findAggregateIdsWithPendingRecords().forEach { aggregateId ->
            val lock = lockManager.acquire(aggregateId) ?: return@forEach

            try {
                processRecords(aggregateId, lock)
            } finally {
                lockManager.release(aggregateId)
            }
        }
    }

    /**
     * Processes all incomplete records for a specific aggregate.
     *
     * @param aggregateId The aggregate ID to process records for
     * @param initialLock The initial lock acquired for this aggregate
     */
    private fun processRecords(
        aggregateId: String,
        initialLock: OutboxLock,
    ) {
        val records = recordRepository.findAllIncompleteRecordsByAggregateId(aggregateId)
        var lock = initialLock

        for (record in records) {
            if (!record.canBeRetried(clock)) break

            lock = lockManager.renew(lock) ?: break

            val success = processRecord(record)

            if (!success && properties.processing.stopOnFirstFailure) {
                log.debug("🛑 Stopping aggregate {} processing due to failure (stopOnFirstFailure=true)", aggregateId)
                break
            }
        }
    }

    /**
     * Processes a single outbox record.
     *
     * @param record The record to process
     * @return true if processing was successful, false otherwise
     */
    private fun processRecord(record: OutboxRecord): Boolean =
        try {
            log.debug("⏳ Processing {} for {}", record.eventType, record.aggregateId)
            recordProcessor.process(record)
            record.markCompleted(clock)
            recordRepository.save(record)
            log.debug("✅ Successfully processed {} for {}", record.eventType, record.aggregateId)
            true
        } catch (ex: Exception) {
            handleFailure(record, ex)
            false
        }

    /**
     * Handles processing failures by updating retry count and scheduling next retry.
     *
     * @param record The record that failed processing
     * @param ex The exception that caused the failure
     */
    private fun handleFailure(
        record: OutboxRecord,
        ex: Exception,
    ) {
        log.debug("❌ Failed {} for {}: {}", record.eventType, record.aggregateId, ex.message)

        record.incrementRetryCount()
        if (record.retriesExhausted(properties.retry.maxRetries) || !retryPolicy.shouldRetry(ex)) {
            record.markFailed()
        } else {
            val delay = retryPolicy.nextDelay(record.retryCount)
            record.scheduleNextRetry(delay, clock)
        }

        recordRepository.save(record)
    }

    /**
     * Finds aggregate IDs that have pending records ready for processing.
     *
     * Takes into account configuration like stopOnFirstFailure to exclude
     * aggregates that have failed records when appropriate.
     *
     * @return List of aggregate IDs with pending records
     */
    private fun findAggregateIdsWithPendingRecords(): List<String> {
        val excludedAggregateIds = mutableListOf<String>()

        if (properties.processing.stopOnFirstFailure) {
            excludedAggregateIds.addAll(recordRepository.findAggregateIdsWithFailedRecords())
        }

        return recordRepository
            .findAggregateIdsWithPendingRecords(status = NEW)
            .filterNot(excludedAggregateIds::contains)
    }
}
