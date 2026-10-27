package io.namastack.example.delivery.billing

import io.namastack.outbox.annotation.OutboxEvent
import java.util.UUID

@OutboxEvent(key = "'order:' + #root.orderId")
data class InvoiceIssued(
    val invoiceId: UUID,
    val orderId: UUID,
    val amountCents: Long,
)
