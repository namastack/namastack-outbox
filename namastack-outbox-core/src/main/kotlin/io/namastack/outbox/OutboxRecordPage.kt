package io.namastack.outbox

/**
 * Records for one storage key in ascending creation-time and record-ID order.
 *
 * Implementations may defer payload and context decoding until [get] is called. Repeated access
 * to a decoded element must return the same record object so processing mutations remain visible.
 * A page holds all fetched rows and their serialized data in memory; it does not limit rows or bytes.
 *
 * @author Roland Beisel
 * @since 1.12.0
 */
interface OutboxRecordPage {
    /** Number of fetched records, available without decoding in optimized readers. */
    val size: Int

    /** Whether this page contains no records. */
    fun isEmpty(): Boolean = size == 0

    /** Decodes the record at [index], preserving its identity on repeated access. */
    operator fun get(index: Int): OutboxRecord<*>
}
