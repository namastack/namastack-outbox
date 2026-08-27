package io.namastack.outbox.handler.method.handler

import io.namastack.outbox.handler.OutboxRecordMetadata
import java.lang.reflect.Method

/**
 * Handler method that can participate in scheduling records of any payload type.
 *
 * Signature: `fun handle(payload: Any, metadata: OutboxRecordMetadata)`
 *
 * @param bean Bean containing the handler method
 * @param method Handler method (must have Any + OutboxRecordMetadata parameters)
 * @param canonicalId Stable routing ID resolved for this handler
 * @param payloadSupport Predicate used to preserve [io.namastack.outbox.handler.OutboxHandler.supports]
 * behavior; annotation-based generic handlers use the default predicate and always participate
 * @param routingAliases Additional IDs that route persisted records to this handler
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
open class GenericHandlerMethod(
    bean: Any,
    method: Method,
    canonicalId: String,
    private val payloadSupport: (Any, OutboxRecordMetadata) -> Boolean = { _, _ -> true },
    routingAliases: Set<String> = emptySet(),
) : OutboxHandlerMethod(bean, method, canonicalId, routingAliases) {
    /**
     * Determines whether this generic handler participates in scheduling a payload.
     *
     * @param payload Payload considered for scheduling
     * @param metadata Handler-specific metadata that would be stored with the record
     * @return `true` when a record should be scheduled for this handler
     */
    open fun supportsPayload(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): Boolean = payloadSupport(payload, metadata)

    /**
     * Invokes the generic handler with a payload and its record metadata.
     *
     * @param payload Deserialized record payload
     * @param metadata Metadata of the record being processed
     * @throws Throwable The original exception raised by the handler
     */
    override fun invoke(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ) = invokeMethod(payload, metadata)
}
