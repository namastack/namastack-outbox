package io.namastack.outbox.observability.metrics

import java.time.Clock
import java.time.Duration

/**
 * Cached metric value shared between background collection and gauge observation.
 *
 * Publishes immutable snapshots so gauge reads never wait for collection. Unknown or expired
 * readings return NaN; a successfully collected zero remains a valid measurement.
 *
 * @param maxAge Maximum age of a reading before it becomes unknown
 * @param clock Clock for reading timestamps and expiry
 *
 * @author Roland Beisel
 * @since 1.12.0
 */
internal class OutboxMetricValue(
    private val maxAge: Duration,
    private val clock: Clock,
) {
    @Volatile
    private var snapshot: OutboxMetricSnapshot? = null

    /** Returns the current reading, or NaN when no fresh reading is available. */
    fun value(): Double {
        val current = snapshot ?: return Double.NaN
        if (!clock.instant().isBefore(current.collectedAt.plus(maxAge))) return Double.NaN
        return current.value
    }

    /** Publishes a successful measurement with its collection timestamp. */
    fun update(value: Double) {
        snapshot = OutboxMetricSnapshot(value, clock.instant())
    }

    /** Marks the reading as unknown after a collection failure. */
    fun invalidate() {
        snapshot = null
    }
}
