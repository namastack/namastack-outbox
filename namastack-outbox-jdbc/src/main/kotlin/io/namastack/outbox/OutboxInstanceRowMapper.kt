package io.namastack.outbox

import io.namastack.outbox.instance.OutboxInstance
import io.namastack.outbox.instance.OutboxInstanceStatus
import org.springframework.jdbc.core.RowMapper
import java.sql.ResultSet
import java.time.OffsetDateTime

/**
 * RowMapper for converting ResultSet rows to OutboxInstance instances.
 *
 * @author Roland Beisel
 * @since 1.0.0
 */
internal class OutboxInstanceRowMapper : RowMapper<OutboxInstance> {
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): OutboxInstance =
        OutboxInstance(
            instanceId = rs.getString("instance_id"),
            hostname = rs.getString("hostname"),
            port = rs.getInt("port"),
            status = OutboxInstanceStatus.valueOf(rs.getString("status")),
            startedAt = rs.getObject("started_at", OffsetDateTime::class.java),
            lastHeartbeat = rs.getObject("last_heartbeat", OffsetDateTime::class.java),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
            updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java),
        )
}
