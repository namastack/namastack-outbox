package io.namastack.outbox.observability.metrics

import java.time.Instant

/**
 * Immutable reading of a single outbox metric.
 *
 * @property value Measured value
 * @property collectedAt Time the measurement completed successfully
 *
 * @author Roland Beisel
 * @since 1.12.0
 */
internal data class OutboxMetricSnapshot(
    val value: Double,
    val collectedAt: Instant,
)
