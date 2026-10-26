package io.namastack.outbox.handler

import java.time.Instant

/**
 * Metadata context for handler selection and outbox record processing.
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
 * @property failureCount Number of failed processing attempts before the current handler invocation
 * @property recordId Persisted record identifier; `null` before record creation during scheduling
 * @property attempt One-based attempt number for the current handler invocation
 * @property isRetry True when the current handler invocation is a retry
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
data class OutboxRecordMetadata(
    val key: String,
    val handlerId: String,
    val createdAt: Instant,
    val context: Map<String, String>,
    val failureCount: Int = 0,
    val recordId: String? = null,
) {
    /**
     * Creates metadata without a persisted record identifier for existing callers and test fixtures.
     *
     * Leaves [recordId] `null`, as during handler selection before a record is created.
     * Preserves the original constructor and its Kotlin default-argument signature.
     */
    constructor(
        key: String,
        handlerId: String,
        createdAt: Instant,
        context: Map<String, String>,
        failureCount: Int = 0,
    ) : this(key, handlerId, createdAt, context, failureCount, null)

    /**
     * Copies metadata while retaining its record identifier.
     *
     * Preserves the original `copy` and Kotlin default-argument signatures for existing callers.
     * Use the generated overload with a `recordId` argument to change the identifier explicitly.
     */
    fun copy(
        key: String = this.key,
        handlerId: String = this.handlerId,
        createdAt: Instant = this.createdAt,
        context: Map<String, String> = this.context,
        failureCount: Int = this.failureCount,
    ): OutboxRecordMetadata = OutboxRecordMetadata(key, handlerId, createdAt, context, failureCount, recordId)

    val attempt: Int
        get() = failureCount + 1

    val isRetry: Boolean
        get() = failureCount > 0
}
