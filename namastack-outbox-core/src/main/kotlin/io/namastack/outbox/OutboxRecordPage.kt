package io.namastack.outbox

import java.time.Clock
import java.time.Instant

/**
 * Records for one storage key in ascending creation-time and record-ID order.
 *
 * Implementations may defer payload and context decoding until [get] is called. Metadata access
 * lets the worker check retry eligibility without resolving the payload type. Repeated access to
 * a decoded element must return the same record object so processing mutations remain visible.
 * Fetched rows and serialized data still occupy memory; a page does not limit rows or bytes.
 *
 * @author Roland Beisel
 * @since 1.12.0
 */
interface OutboxRecordPage : List<OutboxRecord<*>> {
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

/** Defers the existing per-key repository read until its worker first accesses the page. */
internal class DeferredOutboxRecordPage(
    loader: () -> List<OutboxRecord<*>>,
) : AbstractList<OutboxRecord<*>>(),
    OutboxRecordPage {
    private val records by lazy(loader)

    override val size: Int get() = records.size

    override fun get(index: Int): OutboxRecord<*> = records[index]

    override fun recordId(index: Int): String = records[index].id

    override fun nextRetryAt(index: Int): Instant = records[index].nextRetryAt
}
