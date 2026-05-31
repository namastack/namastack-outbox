package io.namastack.demo

import com.google.protobuf.Struct
import com.google.protobuf.Value
import io.namastack.outbox.OutboxPayloadSerializer
import java.util.Base64

/**
 * Example custom serializer using protobuf's well-known Struct type.
 * Encodes the payload as a protobuf Struct and stores it Base64-encoded in the TEXT column.
 *
 * This class demonstrates the pattern users follow to implement a non-JSON serializer.
 * Register it as a named @Bean (not as OutboxPayloadSerializer) so Jackson remains
 * the global default, and reference it via @OutboxEvent(serializer = ProtobufOutboxPayloadSerializer::class).
 */
class ProtobufOutboxPayloadSerializer : OutboxPayloadSerializer {

    override fun serialize(payload: Any): String {
        val struct = toStruct(payload)
        return Base64.getEncoder().encodeToString(struct.toByteArray())
    }

    override fun <T> deserialize(serialized: String, type: Class<T>): T {
        val bytes = Base64.getDecoder().decode(serialized)
        val struct = Struct.parseFrom(bytes)
        @Suppress("UNCHECKED_CAST")
        return fromStruct(struct, type) as T
    }

    private fun toStruct(payload: Any): Struct =
        when (payload) {
            is OrderShippedEvent ->
                Struct.newBuilder()
                    .putFields("orderId", stringValue(payload.orderId))
                    .putFields("destination", stringValue(payload.destination))
                    .build()
            else -> throw IllegalArgumentException(
                "ProtobufOutboxPayloadSerializer does not support ${payload.javaClass.name}",
            )
        }

    private fun fromStruct(struct: Struct, type: Class<*>): Any =
        when (type) {
            OrderShippedEvent::class.java ->
                OrderShippedEvent(
                    orderId = struct.getFieldsOrThrow("orderId").stringValue,
                    destination = struct.getFieldsOrThrow("destination").stringValue,
                )
            else -> throw IllegalArgumentException(
                "ProtobufOutboxPayloadSerializer does not support ${type.name}",
            )
        }

    private fun stringValue(s: String): Value = Value.newBuilder().setStringValue(s).build()
}
