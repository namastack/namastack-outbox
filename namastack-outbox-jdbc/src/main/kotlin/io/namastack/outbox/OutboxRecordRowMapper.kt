package io.namastack.outbox

import org.springframework.jdbc.core.RowMapper
import java.sql.ResultSet
import java.time.OffsetDateTime

/**
 * RowMapper for converting ResultSet rows to OutboxRecord instances.
 *
 * @param recordSerializer Serializer for deserializing payload and context
 *
 * @author Roland Beisel
 * @since 1.1.0
 */
internal class OutboxRecordRowMapper(
    private val recordSerializer: OutboxPayloadSerializer,
) : RowMapper<OutboxRecord<*>> {
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): OutboxRecord<*> {
        val recordType = rs.getString("record_type")
        val clazz = resolveClass(recordType)
        val payload = recordSerializer.deserialize(rs.getString("payload"), clazz)
        val context = deserializeContext(rs.getString("context"))

        return OutboxRecord.restore(
            id = rs.getString("id"),
            recordKey = rs.getString("record_key"),
            payload = payload,
            context = context,
            partition = rs.getInt("partition_no"),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
            status = OutboxRecordStatus.valueOf(rs.getString("status")),
            completedAt = rs.getObject("completed_at", OffsetDateTime::class.java),
            failureCount = rs.getInt("failure_count"),
            failureReason = rs.getString("failure_reason"),
            nextRetryAt = rs.getObject("next_retry_at", OffsetDateTime::class.java),
            handlerId = rs.getString("handler_id"),
            failureException = null,
        )
    }

    /**
     * Deserializes context JSON to Map. Returns empty map if null.
     */
    @Suppress("UNCHECKED_CAST")
    private fun deserializeContext(contextJson: String?): Map<String, String> =
        contextJson?.let {
            recordSerializer.deserialize(it, Map::class.java as Class<Map<String, String>>)
        } ?: emptyMap()

    /**
     * Resolves class by name using thread context class loader.
     */
    private fun resolveClass(className: String): Class<*> =
        try {
            Thread.currentThread().contextClassLoader.loadClass(className)
        } catch (ex: ClassNotFoundException) {
            throw IllegalStateException("Cannot find class for record type $className", ex)
        }
}
