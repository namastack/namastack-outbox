package io.namastack.performance.tooling.command

import io.namastack.performance.tooling.run.ProduceRequest
import io.namastack.performance.tooling.internal.format
import java.time.Duration
import kotlin.math.max

internal class ProduceCommand : ToolingCommand {
    override fun execute(options: CommandLineOptions) {
        val targetRate = options.long("producer-rate")
        val result =
            services(options).performanceTests.produce(
                ProduceRequest(
                    runId = options.string("run-id"),
                    profile = options.string("profile", "steady-state-payments"),
                    targetRate = targetRate,
                    duration = options.duration("producer-duration", Duration.ofMinutes(10)),
                    transactionBatchSize = options.int("producer-batch-size", 1),
                    workers = options.int("producer-workers", 4),
                    recordsPerKey = options.int("records-per-key", 1),
                    measurementWarmup = options.duration("measurement-warmup", Duration.ofSeconds(30)),
                    minimumProducerRateRatio = options.double("min-producer-rate-ratio", 0.99),
                    maximumBacklogGrowthRate = options.double("max-backlog-growth-rate", max(1.0, targetRate * 0.01)),
                    maximumEndBacklog = options.long("max-end-backlog", targetRate * 2),
                    consumerInstances = options.int("instances", 1),
                    consumerBatchSize = options.int("batch-size", 1000),
                    pollInterval = options.string("poll-interval", "100ms"),
                    warmupRecords = options.long("warmup-records", 0),
                ),
            )
        println("plannedRecords=${result.plannedRecords}")
        println("producedRecords=${result.producedRecords}")
        println("producerStartedAt=${result.startedAt}")
        println("producerCompletedAt=${result.completedAt}")
        println("producerDurationSeconds=${format(result.durationSeconds)}")
        println("actualProducerRate=${format(result.actualRate)}")
    }
}
