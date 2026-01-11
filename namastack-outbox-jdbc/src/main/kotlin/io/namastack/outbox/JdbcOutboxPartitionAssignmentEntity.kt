package io.namastack.outbox

import java.time.OffsetDateTime

/**
 * Entity representing a partition assignment to an outbox processor instance.
 *
 * Uses optimistic locking (version field) to ensure safe concurrent updates
 * when multiple instances try to claim the same partition.
 *
 * @author Roland Beisel
 * @since 1.1.0
 */
internal data class JdbcOutboxPartitionAssignmentEntity(
    val partitionNumber: Int,
    val instanceId: String?,
    val version: Long?,
    val updatedAt: OffsetDateTime,
)
