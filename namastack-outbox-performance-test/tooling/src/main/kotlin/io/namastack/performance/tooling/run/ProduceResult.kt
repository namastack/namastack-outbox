package io.namastack.performance.tooling.run

import java.time.Instant

internal data class ProduceResult(
    val plannedRecords: Long,
    val producedRecords: Long,
    val startedAt: Instant,
    val completedAt: Instant,
    val durationSeconds: Double,
    val actualRate: Double,
)
