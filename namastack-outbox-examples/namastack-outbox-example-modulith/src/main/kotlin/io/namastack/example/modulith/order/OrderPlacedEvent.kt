package io.namastack.example.modulith.order

import java.util.UUID

data class OrderPlacedEvent(
    val orderId: UUID,
    val sku: String,
    val amountCents: Long,
)
