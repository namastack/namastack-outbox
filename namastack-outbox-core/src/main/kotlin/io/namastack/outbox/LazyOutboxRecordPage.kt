package io.namastack.outbox

import java.time.Instant
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * Ordered record snapshots with metadata access and cached, deferred decoding.
 *
 * Metadata can be inspected without resolving payload types or deserializing payload and context.
 * Accessing a record decodes it and caches the result, preserving its identity and processing
 * mutations on repeated access. Concurrent readers may decode the same row, but all return the
 * same cached instance. A mapping failure affects only the element being accessed.
 *
 * The page retains its supplied rows and their serialized data; it does not limit rows or bytes.
 * It deliberately exposes no collection operations that implicitly decode its contents.
 *
 * @param rows Snapshots for one storage key, ordered by creation time and record ID
 * @param recordIdOf Extracts a stored record ID without decoding
 * @param nextRetryAtOf Extracts a stored retry time without decoding
 * @param decode Converts a snapshot into a domain record when accessed
 *
 * @author Roland Beisel
 * @since 1.12.0
 */
class LazyOutboxRecordPage<T>(
    private val rows: List<T>,
    private val recordIdOf: (T) -> String,
    private val nextRetryAtOf: (T) -> Instant,
    private val decode: (T) -> OutboxRecord<*>,
) : OutboxRecordPage {
    private val recordCache = AtomicReferenceArray<OutboxRecord<*>?>(rows.size)

    override val size: Int get() = rows.size

    override fun recordId(index: Int): String = recordIdOf(rows[index])

    override fun nextRetryAt(index: Int): Instant = nextRetryAtOf(rows[index])

    override fun get(index: Int): OutboxRecord<*> {
        val cachedRecord = recordCache.get(index)
        if (cachedRecord != null) return cachedRecord

        val decodedRecord = decode(rows[index])
        val previouslyCachedRecord = recordCache.compareAndExchange(index, null, decodedRecord)
        return previouslyCachedRecord ?: decodedRecord
    }
}
