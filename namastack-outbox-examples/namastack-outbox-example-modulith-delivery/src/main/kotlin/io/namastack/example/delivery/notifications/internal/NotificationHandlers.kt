package io.namastack.example.delivery.notifications.internal

import io.github.josipmusa.idempotency.spring.Idempotent
import io.namastack.example.delivery.billing.InvoiceIssued
import io.namastack.example.delivery.orders.OrderPlaced
import io.namastack.outbox.annotation.OutboxHandler
import io.namastack.outbox.handler.OutboxRecordMetadata
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
class NotificationHandlers(
    private val notifications: NotificationRepository,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional(rollbackFor = [Exception::class])
    @Idempotent(
        key = "#metadata.recordId",
        scope = "notifications.order-received",
        completion = "join-transaction",
        waitTimeout = "PT0S",
    )
    @OutboxHandler(id = "notifications.order-received", processingGroup = "notifications")
    fun orderReceived(
        event: OrderPlaced,
        metadata: OutboxRecordMetadata,
    ) {
        recordNotification(event.orderId, "ORDER_RECEIVED")
    }

    @Transactional(rollbackFor = [Exception::class])
    @Idempotent(
        key = "#metadata.recordId",
        scope = "notifications.invoice-issued",
        completion = "join-transaction",
        waitTimeout = "PT0S",
    )
    @OutboxHandler(id = "notifications.invoice-issued", processingGroup = "notifications")
    fun invoiceIssued(
        event: InvoiceIssued,
        metadata: OutboxRecordMetadata,
    ) {
        recordNotification(event.orderId, "INVOICE_ISSUED")
    }

    private fun recordNotification(
        orderId: UUID,
        kind: String,
    ) {
        notifications.save(Notification(orderId = orderId, kind = kind))
        logger.info("Recorded {} notification for order {}", kind, orderId)
    }
}
