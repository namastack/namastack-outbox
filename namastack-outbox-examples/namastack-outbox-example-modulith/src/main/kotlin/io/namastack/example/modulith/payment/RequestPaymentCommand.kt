package io.namastack.example.modulith.payment

import java.util.UUID

data class RequestPaymentCommand(
    val orderId: UUID,
    val amountCents: Long,
)
