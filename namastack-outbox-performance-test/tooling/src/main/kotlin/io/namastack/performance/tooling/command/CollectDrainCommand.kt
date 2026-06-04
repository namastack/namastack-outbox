package io.namastack.performance.tooling.command

internal class CollectDrainCommand : ToolingCommand {
    override fun execute(options: CommandLineOptions) {
        println(services(options).collector.collectDrain(options.collectorContext()))
    }
}
