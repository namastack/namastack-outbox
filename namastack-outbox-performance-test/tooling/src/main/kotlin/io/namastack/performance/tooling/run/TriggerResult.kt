package io.namastack.performance.tooling.run

import java.time.Instant

internal data class TriggerResult(
    val insertedRecords: Int,
    val startedAt: Instant,
    val durationMs: Long,
)
