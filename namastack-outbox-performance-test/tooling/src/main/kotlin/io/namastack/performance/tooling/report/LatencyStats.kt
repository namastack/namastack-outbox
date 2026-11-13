package io.namastack.performance.tooling.report

internal data class LatencyStats(
    val avgSeconds: Double,
    val p50Seconds: Double,
    val p95Seconds: Double,
    val p99Seconds: Double,
    val maxSeconds: Double,
) {
    fun toMap() = mapOf("avgSeconds" to avgSeconds, "p50Seconds" to p50Seconds, "p95Seconds" to p95Seconds, "p99Seconds" to p99Seconds, "maxSeconds" to maxSeconds)
}
