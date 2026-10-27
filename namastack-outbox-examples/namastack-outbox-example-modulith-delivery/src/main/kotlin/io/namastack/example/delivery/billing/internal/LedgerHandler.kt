package io.namastack.example.delivery.billing.internal

import io.github.josipmusa.idempotency.spring.Idempotent
import io.namastack.example.delivery.orders.OrderPlaced
import io.namastack.outbox.annotation.OutboxHandler
import io.namastack.outbox.handler.OutboxRecordMetadata
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class LedgerHandler(
    private val ledger: LedgerRepository,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional(rollbackFor = [Exception::class])
    @Idempotent(
        key = "#metadata.recordId",
        scope = "billing.record-ledger",
        completion = "join-transaction",
        waitTimeout = "PT0S",
    )
    @OutboxHandler(id = "billing.record-ledger", processingGroup = "billing")
    fun recordOrder(
        event: OrderPlaced,
        metadata: OutboxRecordMetadata,
    ) {
        ledger.save(LedgerEntry(orderId = event.orderId, amountCents = event.amountCents))
        logger.info("Ledger entry created for order {}", event.orderId)
    }
}
