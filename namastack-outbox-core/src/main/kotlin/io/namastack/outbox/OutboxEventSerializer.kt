package io.namastack.outbox

/**
 * Abstraction for serializing and deserializing outbox event payloads.
 *
 * This interface defines the contract for converting domain events to/from a storable format.
 * Implementations are responsible for:
 * - Converting event objects to string representation (JSON, XML, etc.)
 * - Converting string representation back to typed event objects
 * - Handling serialization errors gracefully
 *
 * The serialized format is stored in the outbox database as the event payload,
 * which is later retrieved and deserialized during event processing.
 *
 * Default Implementation:
 * The framework provides a Jackson-based implementation (JacksonEventOutboxSerializer)
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
interface OutboxEventSerializer {
    /**
     * Serializes an event object to a string representation.
     *
     * The serialized format is stored in the outbox database and must be deserializable
     * back to the original event type using the deserialize method.
     *
     * @param outboxEvent The event object to serialize
     * @return Serialized representation of the event (typically JSON)
     * @throws Exception if serialization fails
     */
    fun serialize(outboxEvent: Any): String

    /**
     * Deserializes a string representation back to a typed event object.
     *
     * The provided type information is used to determine the target class for deserialization.
     * This ensures type safety when reconstructing event objects from the outbox database.
     *
     * @param serialized The serialized event data (typically JSON)
     * @param type The target class to deserialize into
     * @return The deserialized event object of type T
     * @throws Exception if deserialization fails or type conversion is invalid
     */
    fun <T> deserialize(
        serialized: String,
        type: Class<T>,
    ): T
}
