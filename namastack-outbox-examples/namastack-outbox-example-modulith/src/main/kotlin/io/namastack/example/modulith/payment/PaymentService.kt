package io.namastack.example.modulith.payment

import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class PaymentService(
    private val paymentRepository: PaymentRepository,
    private val events: ApplicationEventPublisher,
) {
    private val logger = LoggerFactory.getLogger(PaymentService::class.java)

    @Transactional
    fun requestPayment(command: RequestPaymentCommand): Payment {
        val (orderId, amountCents) = command

        logger.info("[Payment] Request payment for order {}", orderId)

        val payment = paymentRepository.save(Payment.request(orderId = orderId, amountCents = amountCents))

        events.publishEvent(
            PaymentRequestedEvent(
                paymentId = payment.id,
                orderId = payment.orderId,
                amountCents = payment.amountCents,
            ),
        )
        logger.info("[Payment] Published externalized PaymentRequestedEvent for {}", payment.id)

        return payment
    }

    @Transactional
    fun markCaptured(
        paymentId: UUID,
        providerReference: String,
    ) {
        val payment = paymentRepository.getReferenceById(paymentId)
        payment.capture(providerReference)
        logger.info("[Payment] Captured payment {} with provider reference {}", paymentId, providerReference)
    }
}
