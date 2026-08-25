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
    /** Creates metadata supplied to a primary handler. */
    fun metadata(record: OutboxRecord<*>) =
        OutboxRecordMetadata(
            key = record.key,
            handlerId = record.handlerId,
            createdAt = record.createdAt,
            context = record.context,
            failureCount = record.failureCount,
        )

    /** Creates failure context using an explicit policy or the lazily resolved default policy. */
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
