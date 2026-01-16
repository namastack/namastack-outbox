package io.namastack.outbox

import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant

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
@Table(
    name = "outbox_record",
    indexes = [
        Index(name = "idx_outbox_record_record_key_created", columnList = "recordKey, createdAt"),
        Index(name = "idx_outbox_record_partition_status_retry", columnList = "partitionNo, status, nextRetryAt"),
        Index(name = "idx_outbox_record_status_retry", columnList = "status, nextRetryAt"),
        Index(name = "idx_outbox_record_status", columnList = "status"),
        Index(name = "idx_outbox_record_key_completed_created", columnList = "recordKey, completedAt, createdAt"),
    ],
)
internal data class OutboxRecordEntity(
    @Id
    val id: String,
    @Enumerated(EnumType.STRING)
    val status: OutboxRecordStatus,
    val recordKey: String,
    val recordType: String,
    val payload: String,
    val context: String?,
    val partitionNo: Int,
    val createdAt: Instant,
    val completedAt: Instant?,
    val failureCount: Int,
    val failureReason: String?,
    val nextRetryAt: Instant,
    val handlerId: String,
)
