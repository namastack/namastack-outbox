package io.namastack.outbox

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * Simple sentinel lock entity used to obtain a pessimistic row lock
 * (FOR INSERT / PESSIMISTIC_WRITE) prior to partition bootstrap.
 *
 * The table contains exactly one row (id = 1). Locking this row
 * serializes concurrent bootstrap attempts across all instances
 * without requiring a full table lock.
 */
@Entity
@Table(name = "outbox_partition_lock")
internal data class OutboxPartitionLockEntity(
    @Id
    @Column(name = "id")
    val id: Int = SENTINEL_ID,
) {
    companion object {
        const val SENTINEL_ID: Int = 1
    }
}
