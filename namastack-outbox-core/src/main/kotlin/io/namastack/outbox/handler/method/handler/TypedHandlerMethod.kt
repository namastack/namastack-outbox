package io.namastack.outbox.handler.method.handler

import io.namastack.outbox.handler.OutboxRecordMetadata
import java.lang.reflect.Method
import kotlin.reflect.KClass

/**
 * Handler for records with a specific payload type. Provides type-safe processing.
 *
 * Supports two signatures:
 * - 1 param: `fun handle(payload: T)`
 * - 2 params: `fun handle(payload: T, metadata: OutboxRecordMetadata)`
 *
 * @param bean Bean containing the handler method
 * @param method Handler method (1 or 2 parameters)
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
     * Passes metadata only if method accepts it.
     *
     * @param payload Record payload matching paramType
     * @param metadata Record metadata
     * @throws Throwable Original exception from handler (triggers retry logic)
     */
    fun invoke(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ) {
        if (method.parameterCount == 1) {
            invokeMethod(payload)
        } else {
            invokeMethod(payload, metadata)
        }
    }
}
