package io.namastack.outbox

import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime

/**
 * JPA entity representing an outbox record in the database.
 *
 * This entity maps to the outbox_record table and contains all the necessary
 * fields for tracking outbox events including status, retry information,
 * and partition assignment.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@Entity
@Table(name = "outbox_record")
internal data class OutboxRecordEntity(
    @Id
    val id: String,
    @Enumerated(EnumType.STRING)
    val status: OutboxRecordStatus,
    val recordKey: String,
    val recordType: String,
    val payload: String,
    val partitionNo: Int,
    val createdAt: OffsetDateTime,
    val completedAt: OffsetDateTime?,
    val failureCount: Int,
    val failureReason: String?,
    val nextRetryAt: OffsetDateTime,
    val handlerId: String,
)
