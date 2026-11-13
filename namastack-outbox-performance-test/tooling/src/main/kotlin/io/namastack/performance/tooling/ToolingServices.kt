package io.namastack.performance.tooling

import io.namastack.performance.tooling.collector.CollectorService
import io.namastack.performance.tooling.command.CommandLineOptions
import io.namastack.performance.tooling.jdbc.JdbcDatabaseConfig
import io.namastack.performance.tooling.jdbc.JdbcPerformanceStore
import io.namastack.performance.tooling.report.MarkdownPerformanceReportWriter
import io.namastack.performance.tooling.run.PerformanceTestService

internal data class ToolingServices(
    val performanceTests: PerformanceTestService,
    val collector: CollectorService,
)

internal object ToolingServicesFactory {
    fun create(options: CommandLineOptions): ToolingServices {
        val store =
            JdbcPerformanceStore(
                JdbcDatabaseConfig(
                    url = options.string("db-url", System.getenv("PERF_DB_URL") ?: "jdbc:postgresql://localhost:5432/outbox_performance_test"),
                    user = options.string("db-user", System.getenv("PERF_DB_USER") ?: "username"),
                    password = options.string("db-password", System.getenv("PERF_DB_PASSWORD") ?: "password"),
                ),
            )
        return ToolingServices(
            performanceTests = PerformanceTestService(store),
            collector = CollectorService(store, MarkdownPerformanceReportWriter),
        )
    }
}
