package io.namastack.outbox

import io.namastack.outbox.instance.OutboxInstanceStatus
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Entity representing an outbox instance in MongoDB.
 *
 * @author Stellar Hold
 * @since 1.5.0
 */
@Document(collection = "outbox_instances")
@CompoundIndex(name = "status_heartbeat_idx", def = "{'status': 1, 'lastHeartbeat': 1}")
internal data class MongoOutboxInstanceEntity(
    @Id
    val instanceId: String,
    val hostname: String,
    val port: Int,
    @Indexed
    val status: OutboxInstanceStatus,
    val startedAt: Instant,
    @Indexed
    val lastHeartbeat: Instant,
    val createdAt: Instant,
    val updatedAt: Instant,
)
