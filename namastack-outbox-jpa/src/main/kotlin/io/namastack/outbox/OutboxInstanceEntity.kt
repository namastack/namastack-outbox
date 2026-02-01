package io.namastack.outbox

import io.namastack.outbox.instance.OutboxInstanceStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant

/**
 * JPA entity representing an outbox processor instance.
 *
 * Stores information about active instances for partition coordination
 * and load balancing across multiple application instances.
 *
 * @author Roland Beisel
 * @since 0.2.0
 */
@Entity
@Table(
    name = "outbox_instance",
    indexes = [
        Index(name = "idx_outbox_instance_status_heartbeat", columnList = "status, last_heartbeat"),
        Index(name = "idx_outbox_instance_last_heartbeat", columnList = "last_heartbeat"),
        Index(name = "idx_outbox_instance_status", columnList = "status"),
    ],
)
internal data class OutboxInstanceEntity(
    @Id
    @Column(name = "instance_id", nullable = false)
    val instanceId: String,
    @Column(name = "hostname", nullable = false)
    val hostname: String,
    @Column(name = "port", nullable = false)
    val port: Int,
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: OutboxInstanceStatus,
    @Column(name = "started_at", nullable = false)
    val startedAt: Instant,
    @Column(name = "last_heartbeat", nullable = false)
    var lastHeartbeat: Instant,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
) {
    /**
     * Updates the last heartbeat timestamp to current time.
     */
    fun updateHeartbeat(timestamp: Instant) {
        lastHeartbeat = timestamp
        updatedAt = timestamp
    }

    /**
     * Updates the instance status.
     */
    fun updateStatus(
        newStatus: OutboxInstanceStatus,
        timestamp: Instant,
    ) {
        status = newStatus
        updatedAt = timestamp
    }
}
