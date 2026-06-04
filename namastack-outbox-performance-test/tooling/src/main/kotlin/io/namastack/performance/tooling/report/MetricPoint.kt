package io.namastack.performance.tooling.report

import java.time.Instant

internal data class MetricPoint(
    val timestamp: Instant,
    val value: Double,
)
