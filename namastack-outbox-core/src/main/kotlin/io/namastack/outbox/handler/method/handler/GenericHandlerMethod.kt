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
 * @param schedulingSupport Predicate used to preserve [io.namastack.outbox.handler.OutboxHandler.supports]
 * behavior; annotation-based generic handlers use the default predicate and always participate
 * @param canonicalId Stable routing ID, or `null` to use the generated legacy ID
 * @param routingAliases Additional IDs that route persisted records to this handler
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
open class GenericHandlerMethod(
    bean: Any,
    method: Method,
    private val schedulingSupport: (Any, OutboxRecordMetadata) -> Boolean = { _, _ -> true },
    canonicalId: String? = null,
    routingAliases: Set<String> = emptySet(),
) : OutboxHandlerMethod(bean, method, canonicalId, routingAliases) {
    /** Determines whether this handler should be scheduled for the given payload and metadata. */
    open fun supportsScheduling(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): Boolean = schedulingSupport(payload, metadata)

    /**
     * Invokes handler with payload and metadata via reflection.
     *
     * @param payload Record payload (any type)
     * @param metadata Record context information
     * @throws Throwable Original exception from handler (triggers retry logic)
     */
    override fun invoke(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ) = invokeMethod(payload, metadata)
}
