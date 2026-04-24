package io.namastack.outbox.handler.method.handler

import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.method.BaseHandlerMethod
import java.lang.reflect.Method

/**
 * Sealed base for regular outbox handler methods.
 *
 * Subclasses: [TypedHandlerMethod] for specific types, [GenericHandlerMethod] for any type.
 *
 * @param bean Bean containing the handler method
 * @param method Handler method for reflection
 *
 * @author Roland Beisel
 * @since 1.0.0
 */
sealed class OutboxHandlerMethod(
    bean: Any,
    method: Method,
) : BaseHandlerMethod(bean, method) {
    /**
     * Determines whether this handler should be scheduled for the given payload.
     *
     * Default implementation returns true for all handlers.
     */
    open fun supportsScheduling(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): Boolean = true
}
