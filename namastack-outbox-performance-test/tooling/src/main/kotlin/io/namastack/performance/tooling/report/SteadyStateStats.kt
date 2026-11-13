package io.namastack.performance.tooling.report

internal data class SteadyStateStats(
    val actualProducerDurationSeconds: Double,
    val actualProducerRate: Double,
    val producerRateCompliant: Boolean,
    val processingRateDuringProduction: Double,
    val stabilizedProcessingRate: Double,
    val backlogGrowthRate: Double,
    val backlogAtProducerStop: Long,
    val maximumBacklog: Long,
    val drainDurationSeconds: Double,
    val sustainable: Boolean,
    val latency: LatencyStats,
    val productionSamples: List<BacklogSample>,
) {
    fun toMap() =
        mapOf(
            "actualProducerDurationSeconds" to actualProducerDurationSeconds,
            "actualProducerRate" to actualProducerRate,
            "producerRateCompliant" to producerRateCompliant,
            "processingRateDuringProduction" to processingRateDuringProduction,
            "stabilizedProcessingRate" to stabilizedProcessingRate,
            "backlogGrowthRate" to backlogGrowthRate,
            "backlogAtProducerStop" to backlogAtProducerStop,
            "maximumBacklog" to maximumBacklog,
            "drainDurationSeconds" to drainDurationSeconds,
            "sustainable" to sustainable,
            "latency" to latency.toMap(),
        )
}
