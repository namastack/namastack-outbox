package io.namastack.performance.tooling.jdbc

import io.namastack.performance.tooling.record.PerformanceRecord
import io.namastack.performance.tooling.internal.copyValue
import java.io.InputStream
import kotlin.math.min

internal class JdbcSeedInputStream(
    private val runId: String,
    private val records: Iterator<PerformanceRecord>,
) : InputStream() {
    private var bytes = ByteArray(0)
    private var offset = 0

    override fun read(): Int {
        if (!ensureBytes()) return -1
        return bytes[offset++].toInt() and 0xff
    }

    override fun read(
        target: ByteArray,
        targetOffset: Int,
        length: Int,
    ): Int {
        if (!ensureBytes()) return -1
        val count = min(length, bytes.size - offset)
        bytes.copyInto(target, targetOffset, offset, offset + count)
        offset += count
        return count
    }

    private fun ensureBytes(): Boolean {
        if (offset < bytes.size) return true
        if (!records.hasNext()) return false
        val record = records.next()
        bytes = (listOf(runId, *record.values().toTypedArray()).joinToString("\t") { copyValue(it) } + "\n").toByteArray()
        offset = 0
        return true
    }
}
