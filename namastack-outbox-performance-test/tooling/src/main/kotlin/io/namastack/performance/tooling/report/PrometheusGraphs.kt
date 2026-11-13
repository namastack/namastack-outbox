package io.namastack.performance.tooling.report

internal data class PrometheusGraphs(
    val throughput: List<MetricSeries>,
    val throughputByConsumer: List<MetricSeries>,
    val cpu: List<MetricSeries>,
    val memory: List<MetricSeries>,
    val postgres: List<MetricSeries>,
)
