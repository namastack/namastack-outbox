package io.namastack.performance.tooling.report

import io.namastack.performance.tooling.report.BacklogSample
import io.namastack.performance.tooling.internal.secondsBetween

internal fun rateSeries(
    name: String,
    samples: List<BacklogSample>,
    value: (BacklogSample) -> Long,
) =
    MetricSeries(
        name,
        samples.sortedBy { it.timestamp }.zipWithNext().mapNotNull { (before, after) ->
            val seconds = secondsBetween(before.timestamp, after.timestamp)
            if (seconds <= 0) null else MetricPoint(after.timestamp, (value(after) - value(before)) / seconds)
        },
    )
