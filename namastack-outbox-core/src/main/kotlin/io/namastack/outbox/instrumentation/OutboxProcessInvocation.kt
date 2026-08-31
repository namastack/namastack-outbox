package io.namastack.outbox.instrumentation

import io.namastack.outbox.OutboxRecord

/**
 * Describes one primary or fallback handler invocation.
 *
 * @property record Record being dispatched to a handler.
 * @property handlerKind Whether the primary or fallback handler is being invoked.
 * @property channel Logical name of the outbox runtime.
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
data class OutboxProcessInvocation(
    val record: OutboxRecord<*>,
    val handlerKind: OutboxProcessHandlerKind,
    val channel: String,
)
