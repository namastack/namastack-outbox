package io.namastack.outbox.handler.method.fallback

import io.namastack.outbox.handler.OutboxFailureContext
import java.lang.reflect.Method

/**
 * Typed fallback handler for failures with specific payload type. Provides type-safe error handling.
 *
 * Example: `fun handleFailure(payload: OrderEvent, context: OutboxFailureContext)`
 *
 * @param bean Bean containing the fallback handler method
 * @param method Handler method (must have typed payload + context parameters)
 * @author Roland Beisel
 * @since 1.0.0
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
     * @param context Failure details (exception, attempt count, etc.)
     * @throws Throwable Original exception from fallback handler
     */
    override fun invoke(
        payload: Any,
        context: OutboxFailureContext,
    ) {
        invokeMethod(payload, context)
    }
}
