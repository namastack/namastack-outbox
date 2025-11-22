package io.namastack.outbox

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.OffsetDateTime

/**
 * JPA entity representing a partition assignment to an outbox processor instance.
 *
 * Uses optimistic locking (version field) to ensure safe concurrent updates
 * when multiple instances try to claim the same partition.
 *
 * The partition number is the primary key and must be unique.
 * Each partition is assigned to exactly one instance at a time.
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
@Entity
@Table(name = "outbox_partition")
internal data class OutboxPartitionAssignmentEntity(
    @Id
    @Column(name = "partition_number")
    val partitionNumber: Int,
    @Column(name = "instance_id", nullable = true)
    var instanceId: String?,
    @Version
    @Column(name = "version")
    var version: Long = 0,
    @Column(name = "assigned_at", nullable = false)
    var assignedAt: OffsetDateTime,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime,
) {
    /**
     * Updates the partition assignment to a new instance.
     *
     * @param newInstanceId The ID of the instance claiming this partition
     * @param timestamp The timestamp of the assignment
     */
    fun reassignTo(
        newInstanceId: String,
        timestamp: OffsetDateTime,
    ) {
        instanceId = newInstanceId
        assignedAt = timestamp
        updatedAt = timestamp
    }
}
