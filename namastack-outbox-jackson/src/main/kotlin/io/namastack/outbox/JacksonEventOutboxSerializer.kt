package io.namastack.outbox

import tools.jackson.databind.json.JsonMapper

/**
 * Jackson-based implementation of OutboxEventSerializer.
 *
 * This implementation uses Jackson 3.x (tools.jackson) for JSON serialization and deserialization
 * of outbox event payloads. It leverages Jackson's powerful object mapping capabilities to
 * handle complex domain event structures.
 *
 * Configuration:
 * The JsonMapper is typically configured in OutboxJacksonAutoConfiguration
 * with sensible defaults. Custom configurations can be provided by implementing
 * a custom JsonMapper bean.
 *
 * @param mapper The Jackson JsonMapper instance used for serialization/deserialization
 *
 * @author Roland Beisel
 * @since 0.3.0
 */
class JacksonEventOutboxSerializer(
    private val mapper: JsonMapper,
) : OutboxEventSerializer {
    /**
     * Serializes an event object to JSON string format.
     *
     * Converts any event object to its JSON representation, which is then stored
     * in the outbox database. The serialized JSON must be valid and deserializable
     * back to the original event type.
     *
     * @param outboxEvent The event object to serialize (typically a domain event)
     * @return JSON string representation of the event
     * @throws Exception if the object cannot be serialized to JSON
     */
    override fun serialize(outboxEvent: Any): String = mapper.writeValueAsString(outboxEvent)

    /**
     * Deserializes a JSON string back to a typed event object.
     *
     * Reconstructs the event object from its JSON representation stored in the outbox database.
     * The provided type parameter ensures the JSON is correctly deserialized to the expected
     * event class, enabling type-safe event processing.
     *
     * @param serialized JSON string representation of the event
     * @param type The target event class to deserialize into
     * @return The deserialized event object of the specified type
     * @throws Exception if the JSON cannot be deserialized or type mismatch occurs
     */
    override fun <T> deserialize(
        serialized: String,
        type: Class<T>,
    ): T = mapper.readValue(serialized, type)
}
