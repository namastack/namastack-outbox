package io.namastack.outbox

/**
 * Signals that an outbox record references a handler that is unavailable on this instance.
 *
 * @param recordId ID of the affected outbox record
 * @param recordKey Key of the affected outbox record
 * @param handlerId ID of the unavailable handler
 * @author Roland Beisel
 * @since 1.9.0
 */
class OutboxHandlerNotFoundException(
    val recordId: String,
    val recordKey: String,
    val handlerId: String,
) : IllegalStateException("No handler with id $handlerId")
