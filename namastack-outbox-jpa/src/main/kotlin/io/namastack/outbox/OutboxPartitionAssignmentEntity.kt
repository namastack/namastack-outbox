package io.namastack.outbox

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant

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
    @Column(name = "partition_number", nullable = false)
    val partitionNumber: Int,
    @Column(name = "instance_id", nullable = true)
    var instanceId: String?,
    @Version
    @Column(name = "version", nullable = false)
    val version: Long? = null,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
)
