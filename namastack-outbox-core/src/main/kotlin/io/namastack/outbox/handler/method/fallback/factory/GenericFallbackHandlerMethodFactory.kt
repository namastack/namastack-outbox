package io.namastack.outbox.handler.method.fallback.factory

import io.namastack.outbox.handler.OutboxFailureContext
import io.namastack.outbox.handler.OutboxHandlerWithFallback
import io.namastack.outbox.handler.method.fallback.GenericFallbackHandlerMethod
import io.namastack.outbox.handler.method.fallback.OutboxFallbackHandlerMethod
import io.namastack.outbox.handler.method.internal.ReflectionUtils
import java.lang.reflect.Method

/**
 * Factory for creating generic fallback handler methods.
 *
 * Signature: `fun handleFailure(payload: Any, context: OutboxFailureContext)`
 *
 * @author Roland Beisel
 * @since 0.5.0
 */
class GenericFallbackHandlerMethodFactory : OutboxFallbackHandlerMethodFactory {
    /**
     * Checks if method matches generic fallback signature (2 params: Any, context).
     */
    override fun supports(method: Method): Boolean {
        if (method.parameterCount != 2) return false

        val payloadType = method.parameterTypes[0].kotlin
        val failureContext = method.parameterTypes[1].kotlin

        return payloadType == Any::class &&
            failureContext == OutboxFailureContext::class
    }

    /**
     * Creates generic fallback handler wrapper.
     * Signature validation is done by supports().
     */
    override fun create(
        bean: Any,
        method: Method,
    ): OutboxFallbackHandlerMethod = GenericFallbackHandlerMethod(bean, method)

    /**
     * Creates generic fallback handler from OutboxHandlerWithFallback interface.
     */
    fun createFromInterface(bean: OutboxHandlerWithFallback): GenericFallbackHandlerMethod {
        val method = ReflectionUtils.findMethod(bean, "handleFailure", 2)
        return GenericFallbackHandlerMethod(bean, method)
    }
}
