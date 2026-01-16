package io.namastack.outbox

import java.time.Instant

/**
 * Entity representing an outbox record in the database.
 *
 * This entity maps to the outbox_record table and contains all the necessary
 * fields for tracking outbox events including status, retry information,
 * and partition assignment.
 *
 * @author Roland Beisel
 * @since 1.1.0
 */
internal data class JdbcOutboxRecordEntity(
    val id: String,
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
