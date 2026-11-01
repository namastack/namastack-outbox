package io.namastack.performance.tooling.collector

import io.namastack.performance.tooling.internal.secondsBetween
import io.namastack.performance.tooling.report.BacklogSample

/** Weight intervals by elapsed time so a short final sample cannot dominate the reported rate. */
internal fun averageSampleRate(
    samples: List<BacklogSample>,
    value: (BacklogSample) -> Long,
): Double {
    val ordered = samples.sortedBy { it.timestamp }
    if (ordered.size < 2) return 0.0
    val elapsed = secondsBetween(ordered.first().timestamp, ordered.last().timestamp)
    return if (elapsed <= 0) 0.0 else (value(ordered.last()) - value(ordered.first())) / elapsed
}
