package io.namastack.outbox

import io.namastack.outbox.partition.PartitionAssignment
import org.springframework.jdbc.core.RowMapper
import java.sql.ResultSet
import java.time.OffsetDateTime

/**
 * RowMapper for converting ResultSet rows to PartitionAssignment instances.
 *
 * @author Roland Beisel
 * @since 1.0.0
 */
internal class PartitionAssignmentRowMapper : RowMapper<PartitionAssignment> {
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): PartitionAssignment =
        PartitionAssignment(
            partitionNumber = rs.getInt("partition_number"),
            instanceId = rs.getString("instance_id"),
            updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java),
            version = rs.getLong("version"),
        )
}
