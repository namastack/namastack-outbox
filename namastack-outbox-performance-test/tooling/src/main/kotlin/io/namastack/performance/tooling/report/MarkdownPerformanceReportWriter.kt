package io.namastack.performance.tooling.report

import io.namastack.outbox.partition.PartitionHasher
import io.namastack.performance.tooling.report.BacklogSample
import io.namastack.performance.tooling.report.EnvironmentInfo
import io.namastack.performance.tooling.report.PartitionStats
import io.namastack.performance.tooling.report.PerformanceReportWriter
import io.namastack.performance.tooling.report.ReportContext
import io.namastack.performance.tooling.run.RunInfo
import io.namastack.performance.tooling.run.StatusCounts
import io.namastack.performance.tooling.report.SteadyStateStats
import io.namastack.performance.tooling.internal.format
import io.namastack.performance.tooling.internal.jsonMapper
import io.namastack.performance.tooling.internal.secondsBetween
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

internal object MarkdownPerformanceReportWriter : PerformanceReportWriter {
    override fun writeDrainReport(
        context: ReportContext,
        run: RunInfo,
        counts: StatusCounts,
        samples: List<BacklogSample>,
        partitions: PartitionStats,
        environment: EnvironmentInfo,
    ) {
        val prometheus = loadPrometheusGraphs(context, run.startedAt!!, Instant.now())
        val graphs = context.reportDir.resolve("graphs").also { it.createDirectories() }
        writeCommonGraphs(graphs, samples, prometheus, "during backlog drain")
        writeSamples(context.reportDir, samples)
        writeQueries(context)
        writeParameters(context.reportDir, run, partitions, environment)
        val duration = secondsBetween(run.startedAt, run.lastCompletedAt!!)
        context.reportDir.resolve("report.md").writeText(
            """
            # Namastack Outbox Backlog-Drain Performance Report

            ## Result

            | Metric | Value |
            | --- | ---: |
            | Run ID | `${run.runId}` |
            | Result | `${if (counts.isValid(run.expectedRecords)) "VALID" else "INVALID"}` |
            | Profile | `${run.profile}` |
            | Records completed | ${counts.completedRecords} |
            | Records failed | ${counts.failedRecords} |
            | Retry count | ${counts.retryCount} |
            | Duration | ${format(duration)} s |
            | Average throughput | ${format(counts.completedRecords / duration)} records/s |
            | Trigger duration | ${run.triggerDurationMs} ms |

            ## Graphs

            ![Throughput](graphs/throughput-total.svg)

            ![Backlog](graphs/backlog.svg)

            ![Consumer CPU](graphs/consumer-cpu.svg)

            ![Consumer memory](graphs/consumer-memory.svg)

            ![PostgreSQL](graphs/postgres.svg)
            """.trimIndent() + "\n",
        )
    }

    override fun writeSteadyStateReport(
        context: ReportContext,
        run: RunInfo,
        counts: StatusCounts,
        samples: List<BacklogSample>,
        stats: SteadyStateStats,
        partitions: PartitionStats,
        environment: EnvironmentInfo,
    ) {
        val prometheus = loadPrometheusGraphs(context, run.producerStartedAt!!, run.producerCompletedAt!!)
        val graphs = context.reportDir.resolve("graphs").also { it.createDirectories() }
        writeCommonGraphs(graphs, samples, prometheus, "during production")
        SvgGraph.write(
            graphs.resolve("producer-vs-consumer.svg"),
            "Produced vs processed records / second",
            listOf(
                rateSeries("produced", stats.productionSamples) { it.counts.totalRecords() },
                rateSeries("processed", stats.productionSamples) { it.counts.completedRecords },
            ),
        )
        writeSamples(context.reportDir, samples)
        writeQueries(context)
        writeParameters(context.reportDir, run, partitions, environment, mapOf("steadyState" to stats.toMap()))
        context.reportDir.resolve("report.md").writeText(steadyStateMarkdown(run, counts, stats, context.grafanaUrl, partitions, environment))
    }

    override fun steadyStateSummary(
        run: RunInfo,
        counts: StatusCounts,
        stats: SteadyStateStats,
    ) =
        """
        Run ID:                              ${run.runId}
        Records planned / produced:          ${run.expectedRecords} / ${run.producedRecords}
        Records completed / failed:          ${counts.completedRecords} / ${counts.failedRecords}
        Target / actual producer rate:       ${run.producerTargetRate} / ${format(stats.actualProducerRate)} records/s
        Processing rate during production:   ${format(stats.processingRateDuringProduction)} records/s
        Stabilized backlog growth:           ${format(stats.backlogGrowthRate)} records/s
        Backlog at producer stop:             ${stats.backlogAtProducerStop}
        Drain duration:                       ${format(stats.drainDurationSeconds)} s
        Processing latency p50 / p95 / p99:  ${format(stats.latency.p50Seconds)} / ${format(stats.latency.p95Seconds)} / ${format(stats.latency.p99Seconds)} s
        Correctness result:                  ${if (counts.isValid(run.expectedRecords)) "VALID" else "INVALID"}
        Producer rate result:                ${if (stats.producerRateCompliant) "TARGET MET" else "TARGET MISSED"}
        Capacity assessment:                 ${if (stats.sustainable) "SUSTAINABLE" else "UNSUSTAINABLE"}
        Report:                              report.md
        """.trimIndent()

    override fun drainSummary(
        run: RunInfo,
        counts: StatusCounts,
    ): String {
        val duration = secondsBetween(run.startedAt!!, run.lastCompletedAt!!)
        return """
            Run ID:              ${run.runId}
            Records completed:   ${counts.completedRecords}
            Records failed:      ${counts.failedRecords}
            Duration:            ${format(duration)} s
            Average throughput:  ${format(counts.completedRecords / duration)} records/s
            Result:              ${if (counts.isValid(run.expectedRecords)) "VALID" else "INVALID"}
            Report:              report.md
        """.trimIndent()
    }

    private fun loadPrometheusGraphs(
        context: ReportContext,
        start: Instant,
        end: Instant,
    ): PrometheusGraphs {
        Thread.sleep(3_000)
        val client = PrometheusClient(context.prometheusUrl)
        return PrometheusGraphs(
            throughput = client.queryRange(PrometheusQueries.throughput, start, end),
            throughputByConsumer = client.queryRange(PrometheusQueries.throughputByConsumer, start, end),
            cpu = client.queryRange(PrometheusQueries.cpu, start, end),
            memory = client.queryRange(PrometheusQueries.memory, start, end),
            postgres = client.queryRange(PrometheusQueries.postgres, start, end),
        )
    }

    private fun writeCommonGraphs(
        graphs: Path,
        samples: List<BacklogSample>,
        prometheus: PrometheusGraphs,
        suffix: String,
    ) {
        SvgGraph.write(graphs.resolve("throughput-total.svg"), "Processed outbox records / second $suffix", prometheus.throughput)
        SvgGraph.write(graphs.resolve("throughput-by-consumer.svg"), "Processed records / second by consumer $suffix", prometheus.throughputByConsumer)
        SvgGraph.write(graphs.resolve("backlog.svg"), "Remaining outbox backlog", listOf(MetricSeries("new", samples.map { MetricPoint(it.timestamp, it.counts.newRecords.toDouble()) })))
        SvgGraph.write(graphs.resolve("consumer-cpu.svg"), "Consumer process CPU usage $suffix", prometheus.cpu)
        SvgGraph.write(graphs.resolve("consumer-memory.svg"), "Consumer JVM heap bytes $suffix", prometheus.memory)
        SvgGraph.write(graphs.resolve("postgres.svg"), "PostgreSQL commits / second $suffix", prometheus.postgres)
    }

    private fun writeSamples(
        directory: Path,
        samples: List<BacklogSample>,
    ) {
        directory.resolve("samples.csv").writeText(
            buildString {
                appendLine("timestamp,phase,new_records,completed_records,failed_records,retry_count,total_records")
                samples.sortedBy { it.timestamp }.forEach {
                    appendLine("${it.timestamp},${it.phase},${it.counts.newRecords},${it.counts.completedRecords},${it.counts.failedRecords},${it.counts.retryCount},${it.counts.totalRecords()}")
                }
            },
        )
    }

    private fun writeQueries(context: ReportContext) {
        context.reportDir.resolve("prometheus-queries.json").writeText(
            jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                mapOf(
                    "prometheusUrl" to context.prometheusUrl,
                    "throughput" to PrometheusQueries.throughput,
                    "throughputByConsumer" to PrometheusQueries.throughputByConsumer,
                    "cpu" to PrometheusQueries.cpu,
                    "memory" to PrometheusQueries.memory,
                    "postgres" to PrometheusQueries.postgres,
                ),
            ) + "\n",
        )
    }

    private fun writeParameters(
        directory: Path,
        run: RunInfo,
        partitions: PartitionStats,
        environment: EnvironmentInfo,
        additional: Map<String, Any> = emptyMap(),
    ) {
        directory.resolve("parameters.json").writeText(
            jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                run.toMap() + mapOf("partitions" to partitions.toMap(), "environment" to environment.toMap()) + additional,
            ) + "\n",
        )
    }

    private fun steadyStateMarkdown(
        run: RunInfo,
        counts: StatusCounts,
        stats: SteadyStateStats,
        grafanaUrl: String,
        partitions: PartitionStats,
        environment: EnvironmentInfo,
    ): String =
        """
        # Namastack Outbox Steady-State Performance Report

        ## Result

        | Metric | Value |
        | --- | ---: |
        | Run ID | `${run.runId}` |
        | Correctness result | `${if (counts.isValid(run.expectedRecords)) "VALID" else "INVALID"}` |
        | Producer rate result | `${if (stats.producerRateCompliant) "TARGET MET" else "TARGET MISSED"}` |
        | Capacity assessment | `${if (stats.sustainable) "SUSTAINABLE" else "UNSUSTAINABLE"}` |
        | Profile | `${run.profile}` |
        | Records planned | ${run.expectedRecords} |
        | Records produced | ${run.producedRecords} |
        | Records completed | ${counts.completedRecords} |
        | Records failed | ${counts.failedRecords} |
        | Retry count | ${counts.retryCount} |
        | Target producer rate | ${run.producerTargetRate} records/s |
        | Actual producer rate | ${format(stats.actualProducerRate)} records/s |
        | Processing rate during production | ${format(stats.processingRateDuringProduction)} records/s |
        | Stabilized sampled processing rate | ${format(stats.stabilizedProcessingRate)} records/s |
        | Stabilized backlog growth rate | ${format(stats.backlogGrowthRate)} records/s |
        | Backlog at producer stop | ${stats.backlogAtProducerStop} |
        | Maximum sampled backlog | ${stats.maximumBacklog} |
        | Drain duration after producer stop | ${format(stats.drainDurationSeconds)} s |

        ## Processing Latency

        | Metric | Value |
        | --- | ---: |
        | Average | ${format(stats.latency.avgSeconds)} s |
        | p50 | ${format(stats.latency.p50Seconds)} s |
        | p95 | ${format(stats.latency.p95Seconds)} s |
        | p99 | ${format(stats.latency.p99Seconds)} s |
        | Maximum | ${format(stats.latency.maxSeconds)} s |

        ## Parameters

        | Parameter | Value |
        | --- | ---: |
        | Consumer instances | ${run.consumerInstances} |
        | Consumer batch size | ${run.batchSize} |
        | Poll interval | `${run.pollInterval}` |
        | Producer duration configured | ${format(run.producerDurationMs!! / 1_000.0)} s |
        | Producer duration actual | ${format(stats.actualProducerDurationSeconds)} s |
        | Producer workers | ${run.producerWorkers} |
        | Producer transaction batch size | ${run.producerBatchSize} |
        | Minimum accepted producer-rate ratio | ${format(run.minProducerRateRatio!!)} |
        | Records per key | ${run.recordsPerKey} |
        | Distinct keys | ${run.distinctKeys} |
        | Warm-up records | ${run.warmupRecords} |
        | Stabilization window excluded | ${format(run.measurementWarmupMs!! / 1_000.0)} s |
        | Maximum accepted backlog growth | ${format(run.maxBacklogGrowthRate!!)} records/s |
        | Maximum accepted end backlog | ${run.maxEndBacklog} |
        | Partitions used | ${partitions.usedPartitions} / ${PartitionHasher.TOTAL_PARTITIONS} |
        | Records per used partition min / avg / max | ${partitions.minRecords} / ${format(partitions.avgRecords)} / ${partitions.maxRecords} |

        ## Environment

        | Property | Value |
        | --- | --- |
        | Git commit | `${environment.gitCommit}` |
        | Git dirty | `${environment.gitDirty}` |
        | Java | `${environment.javaVersion}` |
        | Operating system | `${environment.operatingSystem}` |
        | Docker server | `${environment.dockerVersion}` |
        | PostgreSQL | `${environment.postgresVersion}` |

        ## Graphs

        ![Produced vs processed throughput](graphs/producer-vs-consumer.svg)

        ![Processing throughput](graphs/throughput-total.svg)

        ![Processing throughput by consumer](graphs/throughput-by-consumer.svg)

        ![Backlog](graphs/backlog.svg)

        ![Consumer CPU](graphs/consumer-cpu.svg)

        ![Consumer memory](graphs/consumer-memory.svg)

        ![PostgreSQL](graphs/postgres.svg)

        ## Notes

        - The producer schedules exactly `target rate × configured duration` records. When local inserts cannot keep up, production takes longer and the producer-rate result becomes `TARGET MISSED`; records are not silently dropped.
        - Capacity is sustainable only when the producer target was met, stabilized backlog growth is at most ${format(run.maxBacklogGrowthRate)} records/s and end backlog is at most ${run.maxEndBacklog}.
        - Latency is measured from `created_at` to `completed_at`, including time spent in the outbox backlog.
        - The Grafana live dashboard is available at `$grafanaUrl`.
        """.trimIndent() + "\n"
}
