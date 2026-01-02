package io.namastack.outbox.handler.method.handler.factory

import io.namastack.outbox.handler.OutboxHandler
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.method.handler.GenericHandlerMethod
import io.namastack.outbox.handler.method.handler.OutboxHandlerMethod
import io.namastack.outbox.handler.method.internal.ReflectionUtils
import java.lang.reflect.Method

/**
 * Factory for creating generic handler methods.
 *
 * Signature: `fun handle(payload: Any, metadata: OutboxRecordMetadata)`
 *
 * @author Roland Beisel
 * @since 1.0.0
 */
class GenericHandlerMethodFactory : OutboxHandlerMethodFactory {
    /**
     * Checks if method matches generic handler signature (2 params: Any, metadata).
     */
    override fun supports(method: Method): Boolean {
        if (method.parameterCount != 2) return false

        val payloadType = method.parameterTypes[0].kotlin
        val metadataType = method.parameterTypes[1].kotlin

        return payloadType == Any::class && metadataType == OutboxRecordMetadata::class
    }

    /**
     * Creates generic handler wrapper.
     * Signature validation is done by supports().
     */
    override fun create(
        bean: Any,
        method: Method,
    ): OutboxHandlerMethod = GenericHandlerMethod(bean, method)

    /**
     * Creates generic handler from OutboxHandler interface.
     */
    fun createFromInterface(bean: OutboxHandler): GenericHandlerMethod {
        val method = ReflectionUtils.findMethod(bean, "handle", 2)
        return GenericHandlerMethod(bean, method)
    }
}
