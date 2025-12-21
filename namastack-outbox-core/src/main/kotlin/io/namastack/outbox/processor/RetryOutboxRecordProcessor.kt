package io.namastack.outbox.processor

import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.retry.OutboxRetryPolicy
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * Processor that handles failed records by scheduling retries.
 *
 * Schedules retry with calculated delay if retries not exhausted and exception is retryable.
 * Otherwise, passes to next processor in chain.
 *
 * @param retryPolicyRegistry Registry for retry policies
 * @param recordRepository Repository for persisting scheduled retries
 * @param clock Clock for retry scheduling
 *
 * @author Roland Beisel
 * @since 0.5.0
 */
class RetryOutboxRecordProcessor(
    private val retryPolicyRegistry: OutboxRetryPolicyRegistry,
    private val recordRepository: OutboxRecordRepository,
    private val clock: Clock,
) : OutboxRecordProcessor() {
    private val log = LoggerFactory.getLogger(RetryOutboxRecordProcessor::class.java)

    /**
     * Processes record by scheduling retry if possible.
     *
     * @return true if retry was scheduled, false if next processor should process
     */
    override fun handle(record: OutboxRecord<*>): Boolean {
        val retryPolicy = retryPolicyRegistry.getByHandlerId(record.handlerId)

        if (shouldRetry(record, retryPolicy)) {
            val delay = retryPolicy.nextDelay(record.failureCount)
            record.scheduleNextRetry(delay, clock)
            recordRepository.save(record)

            log.debug("Scheduled retry #{} for record {} in {}", record.failureCount, record.id, delay)

            return false
        }

        return handleNext(record)
    }

    /**
     * Checks if record can be retried based on retry policy.
     */
    private fun shouldRetry(
        record: OutboxRecord<*>,
        retryPolicy: OutboxRetryPolicy,
    ): Boolean {
        val exhausted = record.retriesExhausted(retryPolicy.maxRetries())
        val retryable = retryPolicy.shouldRetry(getFailureException(record))

        return !exhausted && retryable
    }

    /**
     * Gets failure exception from record.
     * Exception must exist since this processor only handles failed records.
     */
    private fun getFailureException(record: OutboxRecord<*>): Throwable =
        checkNotNull(record.failureException) {
            "Expected failure exception in record ${record.id} but found none"
        }
}
