package io.namastack.demo

import io.namastack.outbox.annotation.OutboxEvent

@OutboxEvent(key = "#this.orderId", serializer = ProtobufOutboxPayloadSerializer::class)
data class OrderShippedEvent(
    val orderId: String,
    val destination: String,
)
