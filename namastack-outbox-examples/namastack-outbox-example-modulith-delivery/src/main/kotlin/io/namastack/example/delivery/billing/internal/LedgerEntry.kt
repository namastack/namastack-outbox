package io.namastack.example.delivery.billing.internal

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

@Table("LEDGER_ENTRY")
data class LedgerEntry(
    @Id val id: UUID = UUID.randomUUID(),
    val orderId: UUID,
    val amountCents: Long,
    @Version var version: Long? = null,
)
