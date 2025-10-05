package io.namastack.springoutbox

import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime

@Entity
@Table(name = "outbox_record")
internal data class OutboxRecordEntity(
    @Id
    val id: String,
    @Enumerated(EnumType.STRING)
    val status: OutboxRecordStatus,
    val aggregateId: String,
    val eventType: String,
    val payload: String,
    val createdAt: OffsetDateTime,
    val completedAt: OffsetDateTime?,
    val retryCount: Int,
    val nextRetryAt: OffsetDateTime,
)
