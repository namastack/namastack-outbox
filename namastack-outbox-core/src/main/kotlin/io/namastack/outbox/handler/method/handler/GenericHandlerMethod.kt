package io.namastack.outbox.handler.method.handler

import io.namastack.outbox.handler.OutboxRecordMetadata
import java.lang.reflect.Method

/**
 * Handler for records with any payload type. Uses runtime type checking.
 *
 * Signature: `fun handle(payload: Any, metadata: OutboxRecordMetadata)`
 *
 * @param bean Bean containing the handler method
 * @param method Handler method (must have Any + OutboxRecordMetadata parameters)
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
sealed class GenericHandlerMethod(
    bean: Any,
    method: Method,
    id: String? = null,
) : OutboxHandlerMethod(bean, method, id) {
    /**
     * Determines whether this handler should be scheduled for the given payload.
     */
    abstract fun supportsScheduling(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): Boolean

    /**
     * Invokes handler with payload and metadata via reflection.
     *
     * @param payload Record payload (any type)
     * @param metadata Record context information
     * @throws Throwable Original exception from handler (triggers retry logic)
     */
    fun invoke(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ) = invokeMethod(payload, metadata)
}
