package io.namastack.outbox.instance

import java.time.Clock
import java.time.Instant

/**
 * Represents an outbox processor instance.
 *
 * Contains information about an active instance for partition coordination
 * and load balancing across multiple application instances.
 *
 * @property instanceId Unique identifier for this instance
 * @property hostname Hostname where this instance is running
 * @property port Port number of the application
 * @property status Current status of the instance (ACTIVE, SHUTTING_DOWN, etc.)
 * @property startedAt Timestamp when the instance was started
 * @property lastHeartbeat Timestamp of the last heartbeat signal
 * @property createdAt Timestamp when this instance record was created
 * @property updatedAt Timestamp when this instance record was last updated
 *
 * @author Roland Beisel
 * @since 0.2.0
 */
data class OutboxInstance(
    val instanceId: String,
    val hostname: String,
    val port: Int,
    val status: OutboxInstanceStatus,
    val startedAt: Instant,
    val lastHeartbeat: Instant,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        /**
         * Factory method creating a new instance snapshot.
         *
         * All timestamps (started, heartbeat, created, updated) are initialized to the current time.
         *
         * @param instanceId Unique identifier for this instance
         * @param hostname Hostname where this instance is running
         * @param port Port number of the application
         * @param status Initial status of the instance
         * @param clock Clock for timestamp generation
         * @return A newly constructed OutboxInstance
         */
        fun create(
            instanceId: String,
            hostname: String,
            port: Int,
            status: OutboxInstanceStatus,
            clock: Clock,
        ): OutboxInstance {
            val now = Instant.now(clock)

            return OutboxInstance(
                instanceId = instanceId,
                hostname = hostname,
                port = port,
                status = status,
                startedAt = now,
                lastHeartbeat = now,
                createdAt = now,
                updatedAt = now,
            )
        }
    }
}
