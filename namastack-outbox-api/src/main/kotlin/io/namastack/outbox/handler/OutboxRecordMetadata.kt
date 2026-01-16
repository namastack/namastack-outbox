package io.namastack.outbox.handler

import java.time.Instant

/**
 * Metadata context for an outbox record during processing.
 *
 * Provides information about the record being processed, including custom context
 * for cross-cutting concerns like distributed tracing, multi-tenancy, or correlation IDs.
 *
 * ## Usage
 *
 * Available to all handlers for context-aware record processing:
 *
 * ```kotlin
 * @OutboxHandler
 * fun handle(payload: Any, metadata: OutboxRecordMetadata) {
 *     val traceId = metadata.context["traceId"]
 *     logger.info("Processing record for key ${metadata.key} with trace $traceId")
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
 * @property context Custom context map for cross-cutting concerns (tracing, tenancy, correlation)
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
data class OutboxRecordMetadata(
    val key: String,
    val handlerId: String,
    val createdAt: Instant,
    val context: Map<String, String>,
)
