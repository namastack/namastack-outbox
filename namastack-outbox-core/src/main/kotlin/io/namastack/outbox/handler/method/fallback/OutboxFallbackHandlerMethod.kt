package io.namastack.outbox.handler.method.fallback

import io.namastack.outbox.handler.OutboxFailureContext
import io.namastack.outbox.handler.method.BaseHandlerMethod
import java.lang.reflect.Method

/**
 * Sealed base for fallback handlers invoked after retry exhaustion or non-retryable failures.
 *
 * Subclasses: [TypedFallbackHandlerMethod] for specific types, [GenericFallbackHandlerMethod] for any type.
 *
 * @param bean Bean containing the fallback handler method
 * @param method Handler method (must have 2 parameters: payload, context)
 * @author Roland Beisel
 * @since 0.5.0
 */
sealed class OutboxFallbackHandlerMethod(
    bean: Any,
    method: Method,
) : BaseHandlerMethod(bean, method) {
    init {
        require(method.parameterCount == 2) {
            "Fallback handler must have 2 parameters (payload, context): $method"
        }
    }

    /**
     * Invokes fallback handler with payload and failure details.
     *
     * @param payload Record payload
     * @param context Failure details (exception, attempt count, etc.)
     * @throws Throwable Original exception from fallback handler
     */
    abstract fun invoke(
        payload: Any,
        context: OutboxFailureContext,
    )
}
