package io.namastack.performance.tooling.command

import java.time.Duration

internal class AwaitCommand : ToolingCommand {
    override fun execute(options: CommandLineOptions) {
        val counts =
            services(options).performanceTests.awaitProcessing(
                expectedRecords = options.long("expected"),
                timeout = options.duration("timeout", Duration.ofMinutes(30)),
            )
        println(counts.properties())
    }
}
