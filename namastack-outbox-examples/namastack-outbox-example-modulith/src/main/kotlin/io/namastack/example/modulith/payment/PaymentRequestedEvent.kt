package io.namastack.example.modulith.payment

import org.springframework.modulith.events.Externalized
import java.util.UUID

@Externalized("payment-requests::#{orderId}")
data class PaymentRequestedEvent(
    val paymentId: UUID,
    val orderId: UUID,
    val amountCents: Long,
)
