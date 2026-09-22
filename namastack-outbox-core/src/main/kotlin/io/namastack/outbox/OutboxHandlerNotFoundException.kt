package io.namastack.outbox

/**
 * Signals that an outbox record references a handler that is unavailable on this instance.
 *
 * @param recordId ID of the affected outbox record
 * @param recordKey Key of the affected outbox record
 * @param handlerId ID of the unavailable handler
 * @param context Deserialized record context
 * @author Roland Beisel
 * @since 1.10.0
 */
class OutboxHandlerNotFoundException(
    val recordId: String,
    val recordKey: String,
    val handlerId: String,
    val context: Map<String, String> = emptyMap(),
) : IllegalStateException("No handler with id $handlerId")
