package io.namastack.demo

import io.namastack.outbox.annotation.OutboxEvent

@OutboxEvent(key = "#this.orderId")
data class OrderCreatedEvent(
    val orderId: String,
    val customerId: String,
)
