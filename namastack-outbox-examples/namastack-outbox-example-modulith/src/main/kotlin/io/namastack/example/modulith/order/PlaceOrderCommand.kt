package io.namastack.example.modulith.order

data class PlaceOrderCommand(
    val sku: String,
    val amountCents: Long,
)
