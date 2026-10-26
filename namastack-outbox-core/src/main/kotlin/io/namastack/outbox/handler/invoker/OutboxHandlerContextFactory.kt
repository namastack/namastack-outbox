package io.namastack.outbox.handler.invoker

import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.handler.OutboxFailureContext
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.processing.OutboxRecordKey
import io.namastack.outbox.retry.OutboxRetryPolicy
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry

/**
 * Builds public handler context values from stored outbox records.
 *
 * The factory keeps record-to-context mapping consistent between primary and fallback invocation.
 * The stored key determines the group, independently of current handler declarations and whether
 * scheduling new grouped records is enabled.
 *
 * @author Roland Beisel
 * @since 1.9.0
 */
internal object OutboxHandlerContextFactory {
    /**
     * Creates the metadata supplied to a primary handler invocation.
     *
     * @param record Stored record being dispatched
     * @return Public metadata projected from the record
     */
    fun metadata(record: OutboxRecord<*>): OutboxRecordMetadata {
        val key = OutboxRecordKey.decode(record.key)

        return OutboxRecordMetadata(
            recordId = record.id,
            key = key.businessKey,
            handlerId = record.handlerId,
            createdAt = record.createdAt,
            context = record.context,
            failureCount = record.failureCount,
            processingGroup = key.processingGroup,
        )
    }

    /**
     * Creates the context supplied to a fallback handler invocation.
     *
     * The explicit handler policy is used when present; otherwise the default policy is resolved
     * lazily by the record's routing ID.
     *
     * @param record Permanently failed record being dispatched
     * @param exception Last exception raised by the primary handler
     * @param retryPolicies Registry used to resolve the default retry policy
     * @param explicitRetryPolicy Handler-specific retry policy, if one was configured
     * @return Failure context describing why processing became permanent
     */
    fun failure(
        record: OutboxRecord<*>,
        exception: Throwable,
        retryPolicies: OutboxRetryPolicyRegistry,
        explicitRetryPolicy: OutboxRetryPolicy? = null,
    ): OutboxFailureContext {
        val policy = explicitRetryPolicy ?: retryPolicies.getByHandlerId(record.handlerId)
        val key = OutboxRecordKey.decode(record.key)

        return OutboxFailureContext(
            recordId = record.id,
            recordKey = key.businessKey,
            createdAt = record.createdAt,
            handlerId = record.handlerId,
            failureCount = record.failureCount,
            lastFailure = exception,
            retriesExhausted = record.retriesExhausted(policy.maxRetries()),
            nonRetryableException = !policy.shouldRetry(exception),
            context = record.context,
            processingGroup = key.processingGroup,
        )
    }
}
