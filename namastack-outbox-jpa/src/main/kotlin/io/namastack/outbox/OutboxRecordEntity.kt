package io.namastack.outbox

import jakarta.persistence.Column
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
    @Column(name = "id", nullable = false)
    val id: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    val status: OutboxRecordStatus,
    @Column(name = "record_key", nullable = false)
    val recordKey: String,
    @Column(name = "record_type", nullable = false)
    val recordType: String,
    @Column(name = "payload", nullable = false)
    val payload: String,
    @Column(name = "context", nullable = true)
    val context: String?,
    @Column(name = "partition_no", nullable = false)
    val partitionNo: Int,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "completed_at", nullable = true)
    val completedAt: Instant?,
    @Column(name = "failure_count", nullable = false)
    val failureCount: Int,
    @Column(name = "failure_reason", nullable = true)
    val failureReason: String?,
    @Column(name = "next_retry_at", nullable = false)
    val nextRetryAt: Instant,
    @Column(name = "handler_id", nullable = false)
    val handlerId: String,
)
