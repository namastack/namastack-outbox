package io.namastack.performance.tooling.command

internal class CollectSteadyStateCommand : ToolingCommand {
    override fun execute(options: CommandLineOptions) {
        println(services(options).collector.collectSteadyState(options.collectorContext()))
    }
}
