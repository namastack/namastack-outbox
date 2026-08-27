package io.namastack.outbox.handler.discovery

import io.namastack.outbox.handler.OutboxRecordMetadata
import java.lang.reflect.Method

/**
 * Unvalidated primary handler declaration and its identity/scheduling configuration.
 *
 * [supportsPayload] preserves the public [io.namastack.outbox.handler.OutboxHandler.supports]
 * behavior for generic interface handlers. Other declaration styles supply an always-true function.
 *
 * @property beanName Spring bean name used for lambda identity and diagnostics
 * @property bean bean that owns the method
 * @property method reflected primary handler method
 * @property payloadType resolved payload type, if the declaration has a payload parameter
 * @property source declaration mechanism used to find the method
 * @property configuredId explicitly configured stable ID, if any
 * @property configuredAliases explicitly configured legacy routing aliases
 * @property lambdaBeanNameId stable bean-name identity for lambda handlers, if applicable
 * @property supportsPayload predicate evaluated for each payload before scheduling a generic handler
 *
 * @author Roland Beisel
 * @since 1.9.0
 */
internal data class HandlerCandidate(
    val beanName: String,
    val bean: Any,
    val method: Method,
    val payloadType: Class<*>?,
    val source: HandlerSource,
    val configuredId: String?,
    val configuredAliases: Set<String>,
    val lambdaBeanNameId: String?,
    val supportsPayload: (Any, OutboxRecordMetadata) -> Boolean,
)
