package io.namastack.outbox.handler

import java.time.OffsetDateTime

/**
 * Metadata context for an outbox record during processing.
 *
 * Provides information about the record being processed. Available to handlers
 * for decision-making and context awareness during record handling.
 *
 * ## Usage
 *
 * Passed to generic handlers to provide context about the record:
 *
 * ```kotlin
 * @OutboxHandler
 * fun handle(payload: Any, metadata: OutboxRecordMetadata) {
 *     logger.info("Processing record created at ${metadata.createdAt} for key ${metadata.key}")
 *     when (payload) {
 *         is OrderCreated -> handleOrder(payload, metadata)
 *         else -> logger.warn("Unknown payload type for handler ${metadata.handlerId}")
 *     }
 * }
 * ```
 *
 * @property key Logical group identifier for ordered processing of records in the same group
 * @property handlerId Unique identifier of the handler that will process this record
 * @property createdAt Timestamp when the record was created in the outbox
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
data class OutboxRecordMetadata(
    val key: String,
    val handlerId: String,
    val createdAt: OffsetDateTime,
)
