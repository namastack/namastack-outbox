package io.namastack.outbox

import java.time.Clock
import java.time.OffsetDateTime

/**
 * Represents an outbox processor instance.
 *
 * Contains information about an active instance for partition coordination
 * and load balancing across multiple application instances.
 *
 * @author Roland Beisel
 * @since 0.2.0
 */
data class OutboxInstance(
    val instanceId: String,
    val hostname: String,
    val port: Int,
    val status: OutboxInstanceStatus,
    val startedAt: OffsetDateTime,
    val lastHeartbeat: OffsetDateTime,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
) {
    companion object {
        /**
         * Factory method creating a new instance snapshot.
         * All timestamps (started, heartbeat, created, updated) are initialized to now.
         * @return a newly constructed active OutboxInstance
         */
        fun create(
            instanceId: String,
            hostname: String,
            port: Int,
            status: OutboxInstanceStatus,
            clock: Clock,
        ): OutboxInstance {
            val now = OffsetDateTime.now(clock)

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
