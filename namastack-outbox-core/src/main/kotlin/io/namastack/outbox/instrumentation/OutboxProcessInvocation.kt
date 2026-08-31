package io.namastack.outbox.instrumentation

import io.namastack.outbox.OutboxRecord

/**
 * Describes one primary or fallback handler invocation.
 *
 * @param record Record being dispatched to a handler.
 * @param handlerKind Whether the primary or fallback handler is being invoked.
 * @param channel Logical name of the outbox runtime.
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
data class OutboxProcessInvocation(
    val record: OutboxRecord<*>,
    val handlerKind: OutboxProcessHandlerKind,
    val channel: String,
)
