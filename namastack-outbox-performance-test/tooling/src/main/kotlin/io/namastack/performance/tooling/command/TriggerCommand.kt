package io.namastack.performance.tooling.command

import java.time.Duration

internal class TriggerCommand : ToolingCommand {
    override fun execute(options: CommandLineOptions) {
        val result =
            services(options).performanceTests.trigger(
                runId = options.string("run-id"),
                timeout = options.duration("timeout", Duration.ofMinutes(15)),
            )
        println("insertedRecords=${result.insertedRecords}")
        println("runStartedAt=${result.startedAt}")
        println("triggerDurationMs=${result.durationMs}")
    }
}
