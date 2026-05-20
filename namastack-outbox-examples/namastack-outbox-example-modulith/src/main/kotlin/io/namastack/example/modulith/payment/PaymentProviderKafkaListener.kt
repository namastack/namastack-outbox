package io.namastack.example.modulith.payment

import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class PaymentProviderKafkaListener(
    private val paymentService: PaymentService,
) {
    private val logger = LoggerFactory.getLogger(PaymentProviderKafkaListener::class.java)

    @KafkaListener(topics = ["payment-requests"], groupId = "mock-payment-provider")
    fun charge(
        event: PaymentRequestedEvent,
        @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) key: String?,
    ) {
        val providerReference = "mock-${UUID.randomUUID()}"

        logger.info(
            "[External Payment Provider] Received payment {} for order {} via Kafka key {}",
            event.paymentId,
            event.orderId,
            key,
        )

        paymentService.markCaptured(
            paymentId = event.paymentId,
            providerReference = providerReference,
        )
    }
}
