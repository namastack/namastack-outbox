package io.namastack.performance.tooling.jdbc

import io.namastack.performance.tooling.record.PerformanceRecord
import io.namastack.performance.tooling.store.OutboxRecordWriter
import org.postgresql.PGConnection
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.sql.Connection
import java.time.Instant

/** Optional load-generator transport; consumer capacity results must identify this producer mode. */
internal class JdbcCopyOutboxRecordWriter(
    private val connection: Connection,
) : OutboxRecordWriter {
    private val buffer = CopyBuffer()
    private val output = DataOutputStream(buffer)
    private val copy = connection.unwrap(PGConnection::class.java).copyAPI

    init {
        connection.autoCommit = false
    }

    override fun append(records: List<PerformanceRecord>) {
        buffer.reset()
        output.write(byteArrayOf(80, 71, 67, 79, 80, 89, 10, -1, 13, 10, 0))
        output.writeInt(0)
        output.writeInt(0)
        records.forEach(::writeRecord)
        output.writeShort(-1)
        val copied =
            copy.copyIn(
                """
                COPY outbox_record
                (id, status, record_key, record_type, payload, context, created_at, completed_at,
                 failure_count, failure_reason, next_retry_at, partition_no, handler_id)
                FROM STDIN WITH (FORMAT binary)
                """.trimIndent(),
                buffer.input(),
            )
        check(copied == records.size.toLong()) { "COPY accepted $copied of ${records.size} producer records" }
        connection.commit()
    }

    private fun writeRecord(record: PerformanceRecord) {
        output.writeShort(13)
        writeText(record.id)
        writeText(record.status)
        writeText(record.recordKey)
        writeText(record.recordType)
        writeText(record.payload)
        writeText(record.context)
        writeInstant(record.createdAt)
        writeInstant(record.completedAt)
        writeInt(record.failureCount)
        writeText(record.failureReason)
        writeInstant(record.nextRetryAt)
        writeInt(record.partition)
        writeText(record.handlerId)
    }

    private fun writeText(value: String?) {
        if (value == null) {
            output.writeInt(-1)
        } else {
            val bytes = value.toByteArray(Charsets.UTF_8)
            output.writeInt(bytes.size)
            output.write(bytes)
        }
    }

    private fun writeInt(value: Int) {
        output.writeInt(Int.SIZE_BYTES)
        output.writeInt(value)
    }

    private fun writeInstant(value: Instant?) {
        if (value == null) {
            output.writeInt(-1)
        } else {
            // Match pgjdbc's microsecond rounding, including carry into the next second.
            val seconds = Math.subtractExact(value.epochSecond, 946_684_800L)
            val micros = Math.addExact(Math.multiplyExact(seconds, 1_000_000L), (value.nano + 500L) / 1_000L)
            output.writeInt(Long.SIZE_BYTES)
            output.writeLong(micros)
        }
    }

    override fun close() {
        connection.close()
    }

    /** Exposes the current bytes without copying; the next append starts after COPY has consumed them. */
    private class CopyBuffer : ByteArrayOutputStream(64 * 1024) {
        fun input() = java.io.ByteArrayInputStream(buf, 0, count)
    }
}
