package io.namastack.outbox

/**
 * Indicates that the current application instance cannot materialize an outbox payload type.
 *
 * This is an instance capability problem, not a handler delivery failure.
 */
class OutboxPayloadTypeNotFoundException(
    val payloadType: String,
    cause: ClassNotFoundException,
) : RuntimeException("Cannot find class for record type $payloadType", cause)

/**
 * Indicates that the handler persisted on a record is not registered on this application instance.
 *
 * This is thrown before a handler is invoked and therefore must not consume delivery retries.
 */
class OutboxHandlerNotFoundException(
    val handlerId: String,
) : RuntimeException("No handler with id $handlerId")
