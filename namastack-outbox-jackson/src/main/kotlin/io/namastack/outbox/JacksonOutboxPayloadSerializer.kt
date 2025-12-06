package io.namastack.outbox

import tools.jackson.databind.json.JsonMapper

/**
 * Jackson-based implementation of OutboxPayloadSerializer.
 *
 * This implementation uses Jackson 3.x (tools.jackson) for JSON serialization and deserialization
 * of outbox payload payloads.
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
class JacksonOutboxPayloadSerializer(
    private val mapper: JsonMapper,
) : OutboxPayloadSerializer {
    /**
     * Serializes a payload object to JSON string format.
     *
     * Converts any payload object to its JSON representation, which is then stored
     * in the outbox database. The serialized JSON must be valid and deserializable
     * back to the original payload type.
     *
     * @param payload The payload object to serialize (typically a domain payload)
     * @return JSON string representation of the payload
     * @throws Exception if the object cannot be serialized to JSON
     */
    override fun serialize(payload: Any): String = mapper.writeValueAsString(payload)

    /**
     * Deserializes a JSON string back to a typed payload object.
     *
     * Reconstructs the payload object from its JSON representation stored in the outbox database.
     * The provided type parameter ensures the JSON is correctly deserialized to the expected
     * payload class, enabling type-safe payload processing.
     *
     * @param serialized JSON string representation of the payload
     * @param type The target payload class to deserialize into
     * @return The deserialized payload object of the specified type
     * @throws Exception if the JSON cannot be deserialized or type mismatch occurs
     */
    override fun <T> deserialize(
        serialized: String,
        type: Class<T>,
    ): T = mapper.readValue(serialized, type)
}
