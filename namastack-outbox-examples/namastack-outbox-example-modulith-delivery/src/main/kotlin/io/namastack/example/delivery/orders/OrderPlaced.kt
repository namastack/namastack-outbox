package io.namastack.example.delivery.orders

import io.namastack.outbox.annotation.OutboxEvent
import java.util.UUID

@OutboxEvent(key = "'order:' + #root.orderId")
data class OrderPlaced(
    val orderId: UUID,
    val description: String,
    val amountCents: Long,
)
