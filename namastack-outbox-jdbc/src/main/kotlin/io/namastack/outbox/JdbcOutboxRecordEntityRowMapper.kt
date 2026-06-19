package io.namastack.outbox

import org.springframework.jdbc.core.RowMapper
import java.sql.ResultSet

/**
 * Maps JDBC result rows to [JdbcOutboxRecordEntity] instances.
 *
 * This row mapper is shared by runtime and external integrations so both use the same SQL row mapping.
 */
class JdbcOutboxRecordEntityRowMapper : RowMapper<JdbcOutboxRecordEntity> {
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): JdbcOutboxRecordEntity =
        JdbcOutboxRecordEntity(
            id = rs.getString("id"),
            status = OutboxRecordStatus.valueOf(rs.getString("status")),
            recordKey = rs.getString("record_key"),
            recordType = rs.getString("record_type"),
            payload = rs.getString("payload"),
            context = rs.getString("context"),
            partitionNo = rs.getInt("partition_no"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            completedAt = rs.getTimestamp("completed_at")?.toInstant(),
            failureCount = rs.getInt("failure_count"),
            failureReason = rs.getString("failure_reason"),
            nextRetryAt = rs.getTimestamp("next_retry_at").toInstant(),
            handlerId = rs.getString("handler_id"),
        )
}
