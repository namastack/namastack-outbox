package io.namastack.outbox.handler.method.fallback.factory

import io.namastack.outbox.handler.OutboxFailureContext
import io.namastack.outbox.handler.OutboxTypedHandlerWithFallback
import io.namastack.outbox.handler.method.fallback.OutboxFallbackHandlerMethod
import io.namastack.outbox.handler.method.fallback.TypedFallbackHandlerMethod
import io.namastack.outbox.handler.method.internal.ReflectionUtils
import java.lang.reflect.Method

/**
 * Factory for creating typed fallback handler methods.
 *
 * Signature: `fun handleFailure(payload: T, context: OutboxFailureContext)`
 *
 * @author Roland Beisel
 * @since 1.0.0
 */
class TypedFallbackHandlerMethodFactory : OutboxFallbackHandlerMethodFactory {
    /**
     * Checks if method matches typed fallback signature (2 params: typed payload, context).
     */
    override fun supports(method: Method): Boolean {
        if (method.parameterCount != 2) return false

        val payloadType = method.parameterTypes[0].kotlin
        val failureContext = method.parameterTypes[1].kotlin

        return payloadType != Any::class &&
            failureContext == OutboxFailureContext::class
    }

    /**
     * Creates typed fallback handler wrapper.
     * Signature validation is done by supports().
     */
    override fun create(
        bean: Any,
        method: Method,
    ): OutboxFallbackHandlerMethod = TypedFallbackHandlerMethod(bean, method)

    /**
     * Creates typed fallback handler from OutboxTypedHandlerWithFallback interface.
     */
    fun createFromInterface(bean: OutboxTypedHandlerWithFallback<*>): TypedFallbackHandlerMethod {
        val method = ReflectionUtils.findMethod(bean, "handleFailure", 2)
        return TypedFallbackHandlerMethod(bean, method)
    }
}
