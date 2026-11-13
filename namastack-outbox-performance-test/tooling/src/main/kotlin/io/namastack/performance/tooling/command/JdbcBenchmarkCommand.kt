package io.namastack.performance.tooling.command

import io.namastack.performance.tooling.benchmark.JdbcBenchmarkDatabase
import io.namastack.performance.tooling.benchmark.JdbcBenchmarkEnvironment
import io.namastack.performance.tooling.benchmark.JdbcBenchmarkFixture
import io.namastack.performance.tooling.benchmark.JdbcProcessingBenchmark
import io.namastack.performance.tooling.benchmark.JdbcReadBenchmark
import java.nio.file.Path
import java.time.Duration

/**
 * Runs one benchmark step against the selected database.
 *
 * @author Roland Beisel
 */
internal class JdbcBenchmarkCommand {
    fun execute(options: CommandLineOptions) {
        val directory = Path.of(options.string("report-dir"))
        JdbcBenchmarkDatabase(options.string("database"), directory, options.int("pool-size", 16)).use { database ->
            when (val action = options.string("action")) {
                "initialize" -> database.initialize()

                "environment" -> JdbcBenchmarkEnvironment(database).write(options.asMap())

                "seed" ->
                    JdbcBenchmarkFixture(database).seed(
                        options.long("history", 1_000_000),
                        options.long("records", 256_000),
                        options.int("records-per-key", 10),
                        options.int("producer-batch-size", 1000),
                        options.int("producer-workers", 8),
                        options.string("run-id", "jdbc-read"),
                        options.long("deferred", 0),
                    )

                "await-drain" -> JdbcProcessingBenchmark(database).awaitDrain(options.string("run-id"))

                "await-cluster" -> JdbcProcessingBenchmark(database).awaitCluster(options.int("instances", 8))

                "produce" ->
                    JdbcProcessingBenchmark(database).run(
                        options.string("run-id"),
                        options.duration("duration", Duration.ofSeconds(30)),
                        options.long("rate", 20_000),
                        options.int("records-per-key", 10),
                        options.int("producer-batch-size", 1000),
                        options.int("producer-workers", 16),
                        options.string("delete", "false").toBooleanStrict(),
                        options.string("label", "current"),
                        options.string("producer-only", "false").toBooleanStrict(),
                    )

                "read" ->
                    JdbcReadBenchmark(database).run(
                        options.duration("duration", Duration.ofSeconds(20)),
                        options.int("readers", 1),
                        options.int("batch-size", 2000),
                        options.string("strict", "true").toBooleanStrict(),
                        options.string("decode", "true").toBooleanStrict(),
                        options.string("label", "current"),
                    )

                else -> error("Unknown JDBC benchmark action: $action")
            }
        }
    }
}
