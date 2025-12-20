package io.namastack.outbox.handler.method.fallback

import io.namastack.outbox.handler.OutboxFailureContext
import io.namastack.outbox.handler.OutboxRecordMetadata
import java.lang.reflect.Method

/**
 * Typed fallback handler for failures with specific payload type. Provides type-safe error handling.
 *
 * Example: `fun handleFailure(payload: OrderEvent, metadata: ..., context: ...)`
 *
 * @param bean Bean containing the fallback handler method
 * @param method Handler method (must have typed payload + metadata + context parameters)
 * @author Roland Beisel
 * @since 0.5.0
 */
class TypedFallbackHandlerMethod(
    bean: Any,
    method: Method,
) : OutboxFallbackHandlerMethod(bean, method) {
    /**
     * Invokes typed fallback handler via reflection.
     * Exceptions in fallback handlers are logged but don't trigger retries.
     *
     * @param payload Record payload matching paramType
     * @param metadata Record context
     * @param context Failure details (exception, attempt count, etc.)
     * @throws Throwable Original exception from fallback handler
     */
    override fun invoke(
        payload: Any,
        metadata: OutboxRecordMetadata,
        context: OutboxFailureContext,
    ) {
        invokeMethod(payload, metadata, context)
    }
}
