package io.namastack.outbox.handler.method.handler.factory

import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.OutboxTypedHandler
import io.namastack.outbox.handler.method.handler.OutboxHandlerMethod
import io.namastack.outbox.handler.method.handler.TypedHandlerMethod
import io.namastack.outbox.handler.method.internal.ReflectionUtils
import java.lang.reflect.Method

/**
 * Factory for creating typed handler methods.
 *
 * Supports two signatures:
 * - 1 param: `fun handle(payload: T)` where T is a specific type (not Any)
 * - 2 params: `fun handle(payload: T, metadata: OutboxRecordMetadata)` where T is a specific type (not Any)
 *
 * @author Roland Beisel
 * @since 1.0.0
 */
class TypedHandlerMethodFactory : OutboxHandlerMethodFactory {
    /**
     * Checks if method matches typed handler signature.
     * - 1 param: Typed payload (NOT Any)
     * - 2 params: Typed payload (NOT Any) + OutboxRecordMetadata
     */
    override fun supports(method: Method): Boolean {
        val paramCount = method.parameterCount
        if (paramCount != 1 && paramCount != 2) return false

        if (method.parameterTypes.first().kotlin == Any::class) return false

        if (paramCount == 2) {
            return method.parameterTypes[1] == OutboxRecordMetadata::class.java
        }

        return true
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
        val method = ReflectionUtils.findMethod(bean, "handle", 2)
        return TypedHandlerMethod(bean, method)
    }
}
