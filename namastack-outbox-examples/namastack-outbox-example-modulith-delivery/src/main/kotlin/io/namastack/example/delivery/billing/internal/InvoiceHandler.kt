package io.namastack.example.delivery.billing.internal

import io.github.josipmusa.idempotency.spring.Idempotent
import io.namastack.example.delivery.orders.OrderPlaced
import io.namastack.outbox.annotation.OutboxHandler
import io.namastack.outbox.handler.OutboxRecordMetadata
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class InvoiceHandler(
    private val invoices: InvoiceRepository,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional(rollbackFor = [Exception::class])
    @Idempotent(
        key = "#metadata.recordId",
        scope = "billing.issue-invoice",
        completion = "join-transaction",
        waitTimeout = "PT0S",
    )
    @OutboxHandler(id = "billing.issue-invoice", processingGroup = "billing")
    fun issueInvoice(
        event: OrderPlaced,
        metadata: OutboxRecordMetadata,
    ) {
        // Saving publishes InvoiceIssued through the multicaster in this handler transaction.
        val invoice = invoices.save(Invoice.issue(event.orderId, event.amountCents))
        logger.info("Invoice {} created for {} in group {}", invoice.id, metadata.key, metadata.processingGroup)
    }
}
