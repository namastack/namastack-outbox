package io.namastack.performance.tooling.command

import java.time.Duration

internal class AwaitClusterCommand : ToolingCommand {
    override fun execute(options: CommandLineOptions) {
        val status =
            services(options).performanceTests.awaitCluster(
                instances = options.int("instances"),
                timeout = options.duration("timeout", Duration.ofMinutes(2)),
            )
        println("activeInstances=${status.activeInstances}")
        println("assignedPartitions=${status.assignedPartitions}")
    }
}
