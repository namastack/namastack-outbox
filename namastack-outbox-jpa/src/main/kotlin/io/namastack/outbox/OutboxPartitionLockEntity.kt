package io.namastack.outbox

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * JPA entity for pessimistic locking to protect partition assignment initialization.
 *
 * This entity represents a single lock row in the database that is used to coordinate
 * between multiple instances during startup. Only one instance should initialize the
 * partition assignments table. This entity uses pessimistic locking (PESSIMISTIC_WRITE)
 * to ensure only one instance can enter the critical section at a time.
 *
 * The table contains exactly one row with id=1, which serves as a distributed lock.
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
@Entity
@Table(name = "outbox_partition_lock")
internal class OutboxPartitionLockEntity(
    @Id
    val id: Int = 1,
)
