package io.namastack.outbox.handler.method.fallback.factory

import io.namastack.outbox.handler.OutboxTypedHandlerWithFallback
import io.namastack.outbox.handler.method.fallback.OutboxFallbackHandlerMethod
import io.namastack.outbox.handler.method.fallback.TypedFallbackHandlerMethod
import io.namastack.outbox.handler.method.internal.ReflectionUtils
import java.lang.reflect.Method

/**
 * Factory for creating typed fallback handler methods.
 *
 * Signature: `fun handleFailure(payload: T, metadata: OutboxRecordMetadata, context: OutboxFailureContext)`
 *
 * @author Roland Beisel
 * @since 0.6.0
 */
class TypedFallbackHandlerMethodFactory : OutboxFallbackHandlerMethodFactory {
    /**
     * Checks if method matches typed fallback signature (3 params, first NOT Any).
     */
    override fun supports(method: Method): Boolean {
        if (method.parameterCount != 3) return false
        return method.parameterTypes.first().kotlin != Any::class
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
        val method = ReflectionUtils.findMethod(bean, "handleFailure", 3)
        return TypedFallbackHandlerMethod(bean, method)
    }
}
