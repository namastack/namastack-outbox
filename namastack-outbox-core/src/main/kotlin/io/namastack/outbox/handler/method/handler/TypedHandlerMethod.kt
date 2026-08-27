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
 * @param canonicalId Stable routing ID resolved for this handler
 * @param payloadType Resolved payload type used for scheduling lookup
 * @param routingAliases Additional IDs that route persisted records to this handler
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
class TypedHandlerMethod(
    bean: Any,
    method: Method,
    canonicalId: String,
    payloadType: KClass<*> = method.parameterTypes.first().kotlin,
    routingAliases: Set<String> = emptySet(),
) : OutboxHandlerMethod(bean, method, canonicalId, routingAliases) {
    /** Resolved payload type used to index this handler for scheduling. */
    internal val paramType: KClass<*> = payloadType

    /**
     * Invokes the typed handler with a payload and, when declared, record metadata.
     *
     * @param payload Deserialized record payload matching [paramType]
     * @param metadata Metadata of the record being processed
     * @throws Throwable The original exception raised by the handler
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
