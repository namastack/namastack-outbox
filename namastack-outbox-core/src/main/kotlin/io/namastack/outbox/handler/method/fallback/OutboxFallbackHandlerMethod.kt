package io.namastack.outbox.handler.method.fallback

import io.namastack.outbox.handler.OutboxFailureContext
import io.namastack.outbox.handler.method.InvocableHandlerMethod
import java.lang.reflect.Method

/**
 * Reflection wrapper shared by typed and generic fallback declarations.
 *
 * @param bean Bean containing the fallback handler method
 * @param method Handler method (must have 2 parameters: payload, context)
 * @author Roland Beisel
 * @since 1.0.0
 */
open class OutboxFallbackHandlerMethod(
    bean: Any,
    method: Method,
) : InvocableHandlerMethod(bean, method) {
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
    open fun invoke(
        payload: Any,
        context: OutboxFailureContext,
    ) = invokeMethod(payload, context)
}
