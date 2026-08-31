package io.namastack.outbox.instrumentation

/**
 * Describes one outbox scheduling operation.
 *
 * @property payload Payload being scheduled.
 * @property recordKey Effective key used for partitioning and ordering.
 * @property channel Logical name of the outbox runtime.
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
data class OutboxScheduleInvocation(
    val payload: Any,
    val recordKey: String,
    val channel: String,
)
