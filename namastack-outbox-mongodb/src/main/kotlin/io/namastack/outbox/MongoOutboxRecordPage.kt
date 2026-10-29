package io.namastack.outbox

import java.time.Instant
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * MongoDB record page that decodes fetched documents only when their records are accessed.
 *
 * Record IDs and retry times are read without decoding payload or context. Decoded records are
 * cached to preserve their identity and processing mutations. Ordering comes from the bulk query,
 * and workers need no open database cursor or session to access the materialized documents.
 *
 * @param documents Fetched documents for one storage key, ordered by creation time and record ID
 * @param entityMapper Mapper used to decode documents into outbox records
 *
 * @author Roland Beisel
 * @since 1.12.0
 */
internal class MongoOutboxRecordPage(
    private val documents: List<MongoOutboxRecordEntity>,
    private val entityMapper: MongoOutboxRecordEntityMapper,
) : AbstractList<OutboxRecord<*>>(),
    OutboxRecordPage {
    private val recordCache = AtomicReferenceArray<OutboxRecord<*>?>(documents.size)

    /** Number of fetched records, available without decoding their payloads or context. */
    override val size: Int get() = documents.size

    /**
     * Returns the stored record ID without decoding payload or context.
     *
     * @param index Position in the ordered page
     * @return The record ID at the given index
     */
    override fun recordId(index: Int): String = documents[index].id

    /**
     * Returns the stored retry time without decoding payload or context.
     *
     * @param index Position in the ordered page
     * @return The next retry time at the given index
     */
    override fun nextRetryAt(index: Int): Instant = documents[index].nextRetryAt

    /**
     * Decodes a record on first access and returns the cached instance on subsequent accesses.
     *
     * Mapping failures propagate to the worker processing this key.
     *
     * @param index Position in the ordered page
     * @return The decoded record at the given index
     * @throws OutboxPayloadTypeNotFoundException if the record's payload type is unavailable
     */
    override fun get(index: Int): OutboxRecord<*> {
        val cachedRecord = recordCache.get(index)
        if (cachedRecord != null) {
            return cachedRecord
        }

        val decodedRecord = entityMapper.map(documents[index])
        if (recordCache.compareAndSet(index, null, decodedRecord)) {
            return decodedRecord
        }

        // Another reader cached its instance while this record was being decoded.
        return checkNotNull(recordCache.get(index))
    }
}
