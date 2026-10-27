package io.namastack.example.delivery.billing.internal

import io.namastack.example.delivery.billing.InvoiceIssued
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.domain.AbstractAggregateRoot
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

@Table("INVOICE")
class Invoice(
    @Id val id: UUID,
    val orderId: UUID,
    val amountCents: Long,
    @Version var version: Long? = null,
) : AbstractAggregateRoot<Invoice>() {
    companion object {
        fun issue(
            orderId: UUID,
            amountCents: Long,
        ): Invoice {
            val invoice = Invoice(UUID.randomUUID(), orderId, amountCents)
            invoice.registerEvent(InvoiceIssued(invoice.id, orderId, amountCents))

            return invoice
        }
    }
}
