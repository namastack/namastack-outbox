package io.namastack.outbox.handler.method.fallback.factory

import io.namastack.outbox.handler.OutboxFailureContext
import io.namastack.outbox.handler.OutboxHandlerWithFallback
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.method.fallback.GenericFallbackHandlerMethod
import io.namastack.outbox.handler.method.fallback.OutboxFallbackHandlerMethod
import io.namastack.outbox.handler.method.internal.ReflectionUtils
import java.lang.reflect.Method

/**
 * Factory for creating generic fallback handler methods.
 *
 * Signature: `fun handleFailure(payload: Any, metadata: OutboxRecordMetadata, context: OutboxFailureContext)`
 *
 * @author Roland Beisel
 * @since 0.5.0
 */
class GenericFallbackHandlerMethodFactory : OutboxFallbackHandlerMethodFactory {
    /**
     * Checks if method matches generic fallback signature (3 params: Any, metadata, context).
     */
    override fun supports(method: Method): Boolean {
        if (method.parameterCount != 3) return false

        val payloadType = method.parameterTypes[0].kotlin
        val metadataType = method.parameterTypes[1].kotlin
        val failureContext = method.parameterTypes[2].kotlin

        return payloadType == Any::class &&
            metadataType == OutboxRecordMetadata::class &&
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
        val method = ReflectionUtils.findMethod(bean, "handleFailure", 3)
        return GenericFallbackHandlerMethod(bean, method)
    }
}
