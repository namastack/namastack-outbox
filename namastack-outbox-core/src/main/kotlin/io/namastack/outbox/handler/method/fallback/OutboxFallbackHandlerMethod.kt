package io.namastack.outbox.handler.method.fallback

import io.namastack.outbox.handler.OutboxFailureContext
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.method.BaseHandlerMethod
import java.lang.reflect.Method

/**
 * Sealed base for fallback handlers invoked after retry exhaustion or non-retryable failures.
 *
 * Subclasses: [TypedFallbackHandlerMethod] for specific types, [GenericFallbackHandlerMethod] for any type.
 *
 * @param bean Bean containing the fallback handler method
 * @param method Handler method (must have 3 parameters: payload, metadata, context)
 * @author Roland Beisel
 * @since 0.6.0
 */
sealed class OutboxFallbackHandlerMethod(
    bean: Any,
    method: Method,
) : BaseHandlerMethod(bean, method) {
    init {
        require(method.parameterCount == 3) {
            "Fallback handler must have 3 parameters (payload, metadata, context): $method"
        }
    }

    /**
     * Invokes fallback handler with payload, metadata, and failure details.
     *
     * @param payload Record payload
     * @param metadata Record context
     * @param context Failure details (exception, attempt count, etc.)
     * @throws Throwable Original exception from fallback handler
     */
    abstract fun invoke(
        payload: Any,
        metadata: OutboxRecordMetadata,
        context: OutboxFailureContext,
    )
}
