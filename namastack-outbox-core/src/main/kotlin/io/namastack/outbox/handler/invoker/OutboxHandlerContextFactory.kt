package io.namastack.outbox.handler.invoker

import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.handler.OutboxFailureContext
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.retry.OutboxRetryPolicy
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry

/**
 * Builds public handler context values from stored outbox records.
 *
 * The factory keeps record-to-context mapping consistent between primary and fallback invocation.
 *
 * @author Roland Beisel
 * @since 1.8.1
 */
internal object OutboxHandlerContextFactory {
    /**
     * Creates the metadata supplied to a primary handler invocation.
     *
     * @param record Stored record being dispatched
     * @return Public metadata projected from the record
     */
    fun metadata(record: OutboxRecord<*>): OutboxRecordMetadata =
        OutboxRecordMetadata(
            key = record.key,
            handlerId = record.handlerId,
            createdAt = record.createdAt,
            context = record.context,
            failureCount = record.failureCount,
        )

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

        return OutboxFailureContext(
            recordId = record.id,
            recordKey = record.key,
            createdAt = record.createdAt,
            handlerId = record.handlerId,
            failureCount = record.failureCount,
            lastFailure = exception,
            retriesExhausted = record.retriesExhausted(policy.maxRetries()),
            nonRetryableException = !policy.shouldRetry(exception),
            context = record.context,
        )
    }
}
