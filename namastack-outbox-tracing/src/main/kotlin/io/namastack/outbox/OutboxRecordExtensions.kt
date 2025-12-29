package io.namastack.outbox

import io.opentelemetry.api.common.Attributes

/**
 * Converts outbox record metadata to OpenTelemetry span attributes.
 *
 * Creates a set of attributes following semantic conventions for outbox/messaging spans.
 * Includes record identification, status, retry information, and failure details.
 *
 * Attributes added:
 * - outbox.record.id: Unique record identifier
 * - outbox.record.key: Business key for ordering/grouping
 * - outbox.handler: Handler ID that processes this record
 * - outbox.status: Current record status (NEW, COMPLETED, FAILED)
 * - outbox.partition: Partition number for distributed processing
 * - outbox.created_at: Record creation timestamp
 * - outbox.retry.next_at: Next retry attempt timestamp
 * - outbox.failure.count: Number of failed attempts
 * - outbox.failure.exception: Exception message (if failure occurred)
 * - outbox.failure.reason: Failure reason code (if available)
 * - outbox.completed_at: Completion timestamp (if completed)
 *
 * @receiver OutboxRecord to convert
 * @return OpenTelemetry Attributes for span tagging
 */
fun OutboxRecord<*>.toAttributes(): Attributes {
    val builder = Attributes.builder()

    builder.put("outbox.record.id", id)
    builder.put("outbox.record.key", key)
    builder.put("outbox.handler", handlerId)
    builder.put("outbox.status", status.toString())
    builder.put("outbox.partition", partition.toString())
    builder.put("outbox.created_at", createdAt.toString())
    builder.put("outbox.retry.next_at", nextRetryAt.toString())
    builder.put("outbox.failure.count", failureCount.toString())

    failureException?.message?.let { builder.put("outbox.failure.exception", it) }
    failureReason?.let { builder.put("outbox.failure.reason", it) }
    completedAt?.let { builder.put("outbox.completed_at", it.toString()) }

    return builder.build()
}
