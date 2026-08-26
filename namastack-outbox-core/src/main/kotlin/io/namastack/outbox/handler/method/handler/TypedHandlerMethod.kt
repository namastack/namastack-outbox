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
 * @param canonicalId Stable routing ID, or `null` to use the generated method ID
 * @param routingAliases Additional IDs that route persisted records to this handler
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
class TypedHandlerMethod(
    bean: Any,
    method: Method,
    canonicalId: String? = null,
    routingAliases: Set<String> = emptySet(),
) : OutboxHandlerMethod(bean, method, canonicalId, routingAliases) {
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
    override fun invoke(
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
