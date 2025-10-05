package io.namastack.springoutbox

import io.namastack.springoutbox.OutboxRecordStatus.NEW
import io.namastack.springoutbox.lock.OutboxLock
import io.namastack.springoutbox.lock.OutboxLockManager
import io.namastack.springoutbox.retry.OutboxRetryPolicy
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import java.time.Clock

class OutboxProcessingScheduler(
    private val recordRepository: OutboxRecordRepository,
    private val recordProcessor: OutboxRecordProcessor,
    private val lockManager: OutboxLockManager,
    private val retryPolicy: OutboxRetryPolicy,
    private val properties: OutboxProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(OutboxProcessingScheduler::class.java)

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

    private fun processRecords(
        aggregateId: String,
        initialLock: OutboxLock,
    ) {
        val records =
            recordRepository
                .findAllIncompleteRecordsByAggregateId(aggregateId)
                .sortedBy { it.createdAt }

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
