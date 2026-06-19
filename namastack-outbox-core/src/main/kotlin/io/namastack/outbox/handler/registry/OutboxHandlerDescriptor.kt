package io.namastack.outbox.handler.registry

/**
 * Read-only metadata for one registered outbox handler method.
 *
 * This descriptor is intended for operational diagnostics. It exposes stable handler metadata without exposing
 * mutable registry internals or handler method invocation APIs.
 *
 * @property id stable handler id used by persisted outbox records
 * @property kind typed or generic handler kind
 * @property payloadType fully qualified payload type for typed handlers, or null for generic handlers
 * @property beanClass fully qualified user bean class
 * @property methodName handler method name
 * @property methodSignature Java reflection signature for the handler method
 * @property parameterTypes fully qualified handler method parameter types
 *
 * @author Roland Beisel
 * @since 1.7.0
 */
data class OutboxHandlerDescriptor(
    val id: String,
    val kind: OutboxHandlerKind,
    val payloadType: String?,
    val beanClass: String,
    val methodName: String,
    val methodSignature: String,
    val parameterTypes: List<String>,
)
