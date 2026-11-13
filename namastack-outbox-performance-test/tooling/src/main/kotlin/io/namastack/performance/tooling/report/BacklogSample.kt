package io.namastack.performance.tooling.report

import io.namastack.performance.tooling.run.StatusCounts

import java.time.Instant

internal data class BacklogSample(
    val timestamp: Instant,
    val phase: String,
    val counts: StatusCounts,
)
