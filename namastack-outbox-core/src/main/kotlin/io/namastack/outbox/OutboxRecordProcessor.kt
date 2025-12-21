package io.namastack.outbox

import io.namastack.outbox.handler.OutboxFailureContext
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.invoker.OutboxFallbackHandlerInvoker
import io.namastack.outbox.handler.invoker.OutboxHandlerInvoker
import io.namastack.outbox.retry.OutboxRetryPolicy
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * Processes individual outbox records through their handlers with retry logic.
 *
 * Handles record lifecycle: dispatching to handler, success handling, failure handling
 * with retry logic, and fallback handler invocation.
 *
 * @param recordRepository Repository for persisting record state changes
 * @param handlerInvoker Dispatcher for normal handlers
 * @param fallbackHandlerInvoker Dispatcher for fallback handlers
 * @param retryPolicyRegistry Registry for handler-specific retry policies
 * @param properties Configuration properties
 * @param clock Clock for time calculations
 *
 * @author Roland Beisel
 * @since 0.5.0
 */
class OutboxRecordProcessor(
    private val recordRepository: OutboxRecordRepository,
    private val handlerInvoker: OutboxHandlerInvoker,
    private val fallbackHandlerInvoker: OutboxFallbackHandlerInvoker,
    private val retryPolicyRegistry: OutboxRetryPolicyRegistry,
    private val properties: OutboxProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(OutboxRecordProcessor::class.java)

    /**
     * Processes a single record through its handler.
     *
     * @param record The record to process
     * @return true if record processed successfully (or fallback succeeded), false otherwise
     */
    fun processRecord(record: OutboxRecord<*>): Boolean =
        try {
            log.trace("Processing record {} (key: {}, partition: {})", record.id, record.key, record.partition)

            val metadata = OutboxRecordMetadata(record.key, record.handlerId, record.createdAt)
            handlerInvoker.dispatch(record.payload, metadata)

            handleSuccess(record)
            true
        } catch (ex: Exception) {
            handleFailure(record, ex)
        }

    /**
     * Handles successful record processing.
     *
     * Deletes record if configured, otherwise marks as COMPLETED.
     */
    private fun handleSuccess(record: OutboxRecord<*>) {
        if (properties.processing.deleteCompletedRecords) {
            log.trace("Deleting record {} after successful processing", record.id)
            recordRepository.deleteById(record.id)
        } else {
            log.trace("Marking record {} as COMPLETED", record.id)
            record.markCompleted(clock)
            recordRepository.save(record)
        }
    }

    /**
     * Handles record processing failure with retry logic.
     *
     * If retries exhausted or non-retryable, invokes fallback handler.
     * Otherwise, schedules next retry with calculated delay.
     *
     * @return true if fallback handler succeeded, false otherwise
     */
    private fun handleFailure(
        record: OutboxRecord<*>,
        ex: Exception,
    ): Boolean {
        log.debug("Failed processing record {} for key {}: {}", record.id, record.key, ex.message)

        record.incrementFailureCount()
        record.updateFailureReason(ex.message)

        val retryPolicy = retryPolicyRegistry.getByHandlerId(record.handlerId)
        val retriesExhausted = record.retriesExhausted(retryPolicy.maxRetries())
        val nonRetryable = !retryPolicy.shouldRetry(ex)

        val fallbackSucceeded =
            if (retriesExhausted || nonRetryable) {
                handlePermanentFailure(record, retriesExhausted, nonRetryable)
            } else {
                scheduleRetry(record, retryPolicy)
                false
            }

        recordRepository.save(record)
        return fallbackSucceeded
    }

    /**
     * Schedules next retry attempt with calculated delay.
     */
    private fun scheduleRetry(
        record: OutboxRecord<*>,
        retryPolicy: OutboxRetryPolicy,
    ) {
        val delay = retryPolicy.nextDelay(record.failureCount)
        record.scheduleNextRetry(delay, clock)

        log.debug("Scheduled retry #{} for record {} in {}", record.failureCount, record.id, delay)
    }

    /**
     * Handles permanent failure by invoking fallback handler.
     *
     * Marks record as COMPLETED if fallback succeeds, FAILED otherwise.
     *
     * @return true if fallback succeeded, false otherwise
     */
    private fun handlePermanentFailure(
        record: OutboxRecord<*>,
        retriesExhausted: Boolean,
        nonRetryable: Boolean,
    ): Boolean {
        val fallbackSuccess = invokeFallbackHandler(record, retriesExhausted, nonRetryable)

        if (fallbackSuccess) {
            record.markCompleted(clock)
            log.info(
                "Record {} for key {} marked as COMPLETED after fallback handler succeeded ({} failures)",
                record.id,
                record.key,
                record.failureCount,
            )
        } else {
            record.markFailed()
            log.warn(
                "Record {} for key {} marked as FAILED after {} failures{}",
                record.id,
                record.key,
                record.failureCount,
                if (retriesExhausted) " (retries exhausted)" else " (non-retryable exception)",
            )
        }

        return fallbackSuccess
    }

    /**
     * Invokes fallback handler for permanently failed record.
     *
     * @return true if fallback succeeded, false otherwise
     */
    private fun invokeFallbackHandler(
        record: OutboxRecord<*>,
        retriesExhausted: Boolean,
        nonRetryable: Boolean,
    ): Boolean =
        try {
            val metadata = OutboxRecordMetadata(record.key, record.handlerId, record.createdAt)
            val context =
                OutboxFailureContext(
                    recordId = record.id,
                    failureCount = record.failureCount,
                    lastFailureReason = record.failureReason,
                    handlerId = record.handlerId,
                    retriesExhausted = retriesExhausted,
                    nonRetryableException = nonRetryable,
                )

            fallbackHandlerInvoker.dispatch(record.payload, metadata, context)
        } catch (ex: Exception) {
            log.error("Fallback handler failed for record {}: {}", record.id, ex.message, ex)
            false
        }
}
