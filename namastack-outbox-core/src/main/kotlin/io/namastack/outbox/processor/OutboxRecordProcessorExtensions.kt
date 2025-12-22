package io.namastack.outbox.processor

import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.handler.OutboxFailureContext
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry

/**
 * Creates failure context for fallback handlers.
 *
 * @param handlerException The exception that caused the handler to fail
 * @param retryPolicyRegistry Registry to lookup retry policy
 * @return OutboxFailureContext with all failure details
 */
internal fun OutboxRecord<*>.toFailureContext(
    handlerException: Throwable,
    retryPolicyRegistry: OutboxRetryPolicyRegistry,
): OutboxFailureContext {
    val retryPolicy = retryPolicyRegistry.getByHandlerId(handlerId)

    return OutboxFailureContext(
        recordId = id,
        recordKey = key,
        createdAt = createdAt,
        handlerId = handlerId,
        failureCount = failureCount,
        lastFailure = handlerException,
        retriesExhausted = retriesExhausted(retryPolicy.maxRetries()),
        nonRetryableException = !retryPolicy.shouldRetry(handlerException),
    )
}

/**
 * Converts record to metadata for handler invocation.
 *
 * @return OutboxRecordMetadata with key, handlerId, and createdAt
 */
internal fun OutboxRecord<*>.toMetadata() = OutboxRecordMetadata(key, handlerId, createdAt)
