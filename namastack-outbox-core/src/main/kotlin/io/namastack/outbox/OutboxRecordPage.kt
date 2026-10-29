package io.namastack.outbox

import java.time.Clock
import java.time.Instant

/**
 * Records for one storage key in ascending creation-time and record-ID order.
 *
 * Implementations may defer payload and context decoding until [get] is called. Metadata access
 * lets the worker check retry eligibility without resolving the payload type. Repeated access to
 * a decoded element must return the same record object so processing mutations remain visible.
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

    /**
     * Returns the record ID at [index] without decoding payload or context in optimized readers.
     */
    fun recordId(index: Int): String

    /**
     * Returns the next retry time at [index] without decoding payload or context in optimized readers.
     */
    fun nextRetryAt(index: Int): Instant

    /**
     * Checks whether the next retry time has been reached using the page's metadata.
     *
     * This does not decode payload or context in optimized readers. The worker decides whether
     * to stop or continue when a retry is not yet due.
     *
     * @param index Position in the ordered page
     * @param clock Clock to use for determining the current time
     * @return `true` if the retry time is at or before the current time
     */
    fun isRetryDue(
        index: Int,
        clock: Clock,
    ): Boolean = !nextRetryAt(index).isAfter(Instant.now(clock))
}
