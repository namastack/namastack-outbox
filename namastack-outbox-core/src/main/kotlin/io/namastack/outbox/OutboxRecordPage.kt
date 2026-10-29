package io.namastack.outbox

import java.time.Instant
import java.util.Objects

/**
 * One ordered page of pending records. Metadata can be inspected before payload deserialization.
 * Repeated element access must return the same record object. Pages belong to one processing
 * cycle and must not be retained after its partition assignment is released.
 */
interface OutboxRecordPage : List<OutboxRecord<*>> {
    fun nextRetryAt(index: Int): Instant

    fun recordId(index: Int): String = get(index).id
}

/** Compatibility page: custom repositories keep their existing per-key loading behavior. */
internal class DeferredOutboxRecordPage(
    loader: () -> List<OutboxRecord<*>>,
    private val maxRecords: Int,
) : AbstractList<OutboxRecord<*>>(),
    OutboxRecordPage {
    private val records by lazy(loader)
    override val size: Int get() = if (maxRecords == 0) records.size else minOf(maxRecords, records.size)

    override fun get(index: Int): OutboxRecord<*> = records[Objects.checkIndex(index, size)]

    override fun nextRetryAt(index: Int): Instant = get(index).nextRetryAt
}
