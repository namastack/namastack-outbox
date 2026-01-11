package io.namastack.outbox

import io.namastack.outbox.instance.OutboxInstanceStatus
import java.time.OffsetDateTime

/**
 * Entity representing an outbox processor instance.
 *
 * Stores information about active instances for partition coordination
 * and load balancing across multiple application instances.
 *
 * @author Roland Beisel
 * @since 1.1.0
 */
internal data class JdbcOutboxInstanceEntity(
    val instanceId: String,
    val hostname: String,
    val port: Int,
    val status: OutboxInstanceStatus,
    val startedAt: OffsetDateTime,
    val lastHeartbeat: OffsetDateTime,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)
