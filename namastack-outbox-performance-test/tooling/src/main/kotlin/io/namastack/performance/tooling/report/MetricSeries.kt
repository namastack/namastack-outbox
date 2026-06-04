package io.namastack.performance.tooling.report

internal data class MetricSeries(
    val name: String,
    val points: List<MetricPoint>,
)
