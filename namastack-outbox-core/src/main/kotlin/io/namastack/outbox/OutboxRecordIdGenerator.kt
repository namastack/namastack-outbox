package io.namastack.outbox

import java.util.UUID

/**
 * Generates identifiers for newly scheduled outbox records.
 *
 * Implementations must be thread-safe and return a non-blank, unique identifier for
 * every invocation, including concurrent invocations. The generated identifier is
 * used only as [OutboxRecord.id]; it does not control the record key or partition.
 *
 * @author Hyeonseok Song
 * @since 1.8.1
 */
fun interface OutboxRecordIdGenerator {
    /**
     * Generates an identifier for one newly scheduled outbox record.
     *
     * @return a non-blank identifier that is unique for this invocation
     */
    fun generate(): String
}

/**
 * Default [OutboxRecordIdGenerator] that creates random UUID identifiers.
 *
 * @author Hyeonseok Song
 * @since 1.8.1
 */
class RandomUuidOutboxRecordIdGenerator : OutboxRecordIdGenerator {
    override fun generate(): String = UUID.randomUUID().toString()
}
