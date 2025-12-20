package io.namastack.outbox.handler.method.handler.factory

import io.namastack.outbox.handler.OutboxTypedHandler
import io.namastack.outbox.handler.method.handler.OutboxHandlerMethod
import io.namastack.outbox.handler.method.handler.TypedHandlerMethod
import io.namastack.outbox.handler.method.internal.ReflectionUtils
import java.lang.reflect.Method

/**
 * Factory for creating typed handler methods.
 *
 * Signature: `fun handle(payload: T)` where T is a specific type (not Any).
 */
class TypedHandlerMethodFactory : OutboxHandlerMethodFactory {
    /**
     * Checks if method matches typed handler signature (1 param, NOT Any).
     */
    override fun supports(method: Method): Boolean {
        if (method.parameterCount != 1) return false
        return method.parameterTypes.first().kotlin != Any::class
    }

    /**
     * Creates typed handler wrapper.
     * Signature validation is done by supports().
     */
    override fun create(
        bean: Any,
        method: Method,
    ): OutboxHandlerMethod = TypedHandlerMethod(bean, method)

    /**
     * Creates typed handler from OutboxTypedHandler interface.
     */
    fun createFromInterface(bean: OutboxTypedHandler<*>): TypedHandlerMethod {
        val method = ReflectionUtils.findMethod(bean, "handle", 1)
        return TypedHandlerMethod(bean, method)
    }
}
