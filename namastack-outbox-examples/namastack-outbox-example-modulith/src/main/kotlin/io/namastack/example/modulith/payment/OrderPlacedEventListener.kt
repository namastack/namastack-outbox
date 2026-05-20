package io.namastack.example.modulith.payment

import io.namastack.example.modulith.order.OrderPlacedEvent
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

@Component
class OrderPlacedEventListener(
    private val paymentService: PaymentService,
) {
    @ApplicationModuleListener
    fun on(event: OrderPlacedEvent) {
        paymentService.requestPayment(orderId = event.orderId, amountCents = event.amountCents)
    }
}
