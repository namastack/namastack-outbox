package io.namastack.outbox.handler.method

import io.namastack.outbox.handler.OutboxRecordMetadata
import java.lang.reflect.Method

/**
 * Represents a generic handler method that processes outbox records with any payload type.
 *
 * Generic handlers accept two parameters:
 * - `payload: Any` - The record payload (any type)
 * - `metadata: OutboxRecordMetadata` - Context about the record
 *
 * This is used for multi-type handlers that need to route payloads to different logic
 * based on runtime type checking (using `when` expressions).
 *
 * @param bean The bean instance containing the handler method
 * @param method The actual method to invoke (reflection)
 */
class GenericHandlerMethod(
    bean: Any,
    method: Method,
) : OutboxHandlerMethod(bean, method) {
    /**
     * Invokes the generic handler method with the given payload and metadata.
     *
     * Safely invokes the underlying method via reflection, passing both parameters
     * in the correct order.
     *
     * Example:
     * ```kotlin
     * val handler = GenericHandlerMethod(myBean, myHandleMethod)
     * handler.invoke(OrderCreated(orderId = "123"), metadata)
     * ```
     *
     * @param payload The record payload (any type)
     * @param metadata Record metadata with context information
     * @throws Exception if the handler method throws (will trigger retries)
     */
    fun invoke(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ) {
        require(method.parameterCount == 2) {
            "Generic handler method must have exactly two parameters: $method"
        }

        method.invoke(bean, payload, metadata)
    }
}
