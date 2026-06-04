package io.namastack.performance.tooling.command

internal class ResetOutboxCommand : ToolingCommand {
    override fun execute(options: CommandLineOptions) {
        services(options).performanceTests.resetOutbox()
        println("reset=outbox")
    }
}
