package io.namastack.performance.tooling.command

import io.namastack.performance.tooling.run.SeedRequest

internal class SeedCommand : ToolingCommand {
    override fun execute(options: CommandLineOptions) {
        val result =
            services(options).performanceTests.seed(
                SeedRequest(
                    runId = options.string("run-id"),
                    profile = options.string("profile", "independent-payments"),
                    records = options.long("records"),
                    recordsPerKey = options.int("records-per-key", 1),
                    consumerInstances = options.int("instances", 1),
                    batchSize = options.int("batch-size", 1000),
                    pollInterval = options.string("poll-interval", "100ms"),
                    warmupRecords = options.long("warmup-records", 0),
                ),
            )
        println("seededRecords=${result.seededRecords}")
        println("distinctKeys=${result.distinctKeys}")
        println("seedDurationMs=${result.durationMs}")
    }
}
