package io.namastack.outbox

import io.namastack.outbox.instance.OutboxInstanceStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime

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
@Table(name = "outbox_instance")
internal data class OutboxInstanceEntity(
    @Id
    @Column(name = "instance_id")
    val instanceId: String,
    @Column(name = "hostname", nullable = false)
    val hostname: String,
    @Column(name = "port", nullable = false)
    val port: Int,
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: OutboxInstanceStatus,
    @Column(name = "started_at", nullable = false)
    val startedAt: OffsetDateTime,
    @Column(name = "last_heartbeat", nullable = false)
    var lastHeartbeat: OffsetDateTime,
    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime,
) {
    /**
     * Updates the last heartbeat timestamp to current time.
     */
    fun updateHeartbeat(timestamp: OffsetDateTime) {
        lastHeartbeat = timestamp
        updatedAt = timestamp
    }

    /**
     * Updates the instance status.
     */
    fun updateStatus(
        newStatus: OutboxInstanceStatus,
        timestamp: OffsetDateTime,
    ) {
        status = newStatus
        updatedAt = timestamp
    }
}
