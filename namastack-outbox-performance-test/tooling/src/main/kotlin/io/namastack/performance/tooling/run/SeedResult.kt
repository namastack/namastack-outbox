package io.namastack.performance.tooling.run

internal data class SeedResult(
    val seededRecords: Long,
    val distinctKeys: Long,
    val durationMs: Long,
)
