package io.namastack.performance.tooling.command

import io.namastack.performance.tooling.report.ReportContext
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createDirectories

internal fun CommandLineOptions.collectorContext() =
    ReportContext(
        runId = string("run-id"),
        reportDir = Path.of(string("report-dir")).also { it.createDirectories() },
        prometheusUrl = string("prometheus-url", "http://localhost:9090"),
        grafanaUrl = string("grafana-url", "http://localhost:3000/d/namastack-outbox-performance"),
        timeout = duration("timeout", Duration.ofMinutes(30)),
        sampleInterval = duration("sample-interval", Duration.ofSeconds(5)),
    )
