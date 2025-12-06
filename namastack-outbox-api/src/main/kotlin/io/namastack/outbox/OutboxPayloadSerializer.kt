package io.namastack.outbox

/**
 * Abstraction for serializing and deserializing outbox payloads.
 *
 * This interface defines the contract for converting payloads to/from a storable format.
 * Implementations are responsible for:
 * - Converting payload objects to string representation (JSON, XML, etc.)
 * - Converting string representation back to typed payload objects
 * - Handling serialization errors gracefully
 *
 * The serialized format is stored in the outbox database as the payload,
 * which is later retrieved and deserialized during processing.
 *
 * Default Implementation:
 * The framework provides a Jackson-based implementation (JacksonOutboxPayloadSerializer)
 * that uses Jackson's ObjectMapper for JSON serialization/deserialization.
 *
 * Custom Implementations:
 * Users can provide custom implementations to support different serialization formats
 * (e.g., Protocol Buffers, MessagePack, etc.) by implementing this interface and
 * registering the bean in their Spring configuration.
 *
 * @author Roland Beisel
 * @since 0.3.0
 */
interface OutboxPayloadSerializer {
    /**
     * Serializes a payload object to a string representation.
     *
     * The serialized format is stored in the outbox database and must be deserializable
     * back to the original payload type using the deserialize method.
     *
     * @param payload The payload object to serialize
     * @return Serialized representation of the payload (typically JSON)
     * @throws Exception if serialization fails
     */
    fun serialize(payload: Any): String

    /**
     * Deserializes a string representation back to a typed payload object.
     *
     * The provided type information is used to determine the target class for deserialization.
     * This ensures type safety when reconstructing payload objects from the outbox database.
     *
     * @param serialized The serialized payload data (typically JSON)
     * @param type The target class to deserialize into
     * @return The deserialized payload object of type T
     * @throws Exception if deserialization fails or type conversion is invalid
     */
    fun <T> deserialize(
        serialized: String,
        type: Class<T>,
    ): T
}
