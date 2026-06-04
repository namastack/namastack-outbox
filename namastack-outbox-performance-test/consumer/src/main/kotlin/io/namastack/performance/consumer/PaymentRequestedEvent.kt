package io.namastack.performance.consumer

data class PaymentRequestedEvent(
    val paymentId: String,
    val orderId: String,
    val customerId: String,
    val amount: Double,
    val currency: String,
)

