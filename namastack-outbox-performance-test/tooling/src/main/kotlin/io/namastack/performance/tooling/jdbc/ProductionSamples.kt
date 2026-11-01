package io.namastack.performance.tooling.jdbc

import io.namastack.performance.tooling.report.BacklogSample
import io.namastack.performance.tooling.run.StatusCounts
import java.time.Duration
import java.time.Instant
import kotlin.math.ceil

/** Reconstructs timestamp-based counts after the workload, without full-table scans during production. */
internal fun reconstructProductionSamples(
    start: Instant,
    end: Instant,
    interval: Duration,
    buckets: Map<Long, Pair<Long, Long>>,
): List<BacklogSample> {
    require(!end.isBefore(start) && !interval.isZero && !interval.isNegative)
    val last = ceil(Duration.between(start, end).toNanos().toDouble() / interval.toNanos()).toLong()
    var produced = 0L
    var completed = 0L
    return (0L..last).map { bucket ->
        val counts = buckets[bucket] ?: (0L to 0L)
        produced += counts.first
        completed += counts.second
        check(completed <= produced) { "Completed count exceeds produced count at bucket $bucket" }
        val at = minOf(start.plus(interval.multipliedBy(bucket)), end)
        BacklogSample(
            at,
            if (bucket == last) "PRODUCTION_END" else "PRODUCTION",
            StatusCounts(produced - completed, completed, 0, 0, null),
        )
    }
}
