package io.namastack.outbox.handler.method.handler

import io.namastack.outbox.handler.OutboxRecordMetadata
import java.lang.reflect.Method
import kotlin.reflect.KClass

/**
 * Handler for records with a specific payload type. Provides type-safe processing.
 *
 * Signature: `fun handle(payload: T, metadata: OutboxRecordMetadata)`
 *
 * @param bean Bean containing the handler method
 * @param method Handler method (must have 2 parameters: payload + metadata)
 */
class TypedHandlerMethod(
    bean: Any,
    method: Method,
) : OutboxHandlerMethod(bean, method) {
    /** Payload type extracted from method's first parameter. */
    internal val paramType: KClass<*>
        get() = method.parameterTypes.first().kotlin

    /**
     * Invokes handler with typed payload and metadata.
     *
     * @param payload Record payload matching paramType
     * @param metadata Record metadata
     * @throws Throwable Original exception from handler (triggers retry logic)
     */
    fun invoke(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ) {
        invokeMethod(payload, metadata)
    }
}
