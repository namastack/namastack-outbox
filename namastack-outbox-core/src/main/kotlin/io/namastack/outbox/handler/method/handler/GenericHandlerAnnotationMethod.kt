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
 * @author Aleksander Zamojski
 * @since 1.5.0
 */
class GenericHandlerAnnotationMethod(
    bean: Any,
    method: Method,
) : GenericHandlerMethod(bean, method) {
    /**
     * Determines whether this handler should be scheduled for the given payload.
     */
    override fun supportsScheduling(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): Boolean = true
}
