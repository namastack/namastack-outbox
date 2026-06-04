package io.namastack.performance.tooling.report

import java.nio.file.Path
import java.time.Duration

internal data class ReportContext(
    val runId: String,
    val reportDir: Path,
    val prometheusUrl: String,
    val grafanaUrl: String,
    val timeout: Duration,
    val sampleInterval: Duration,
)
