package io.namastack.outbox.instrumentation

/**
 * Describes one outbox scheduling operation.
 *
 * @param payload Payload being scheduled.
 * @param recordKey Explicit scheduling key, or `"auto-generated"` when the scheduling overload
 * does not accept a key and the persisted key is generated internally.
 * @param channel Logical name of the outbox runtime.
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
data class OutboxScheduleInvocation(
    val payload: Any,
    val recordKey: String,
    val channel: String,
)
