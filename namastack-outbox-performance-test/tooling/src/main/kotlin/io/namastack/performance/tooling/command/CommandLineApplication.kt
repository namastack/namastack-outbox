package io.namastack.performance.tooling.command

/** Entry point for the Docker runner's benchmark steps. */
internal class CommandLineApplication {
    fun run(args: Array<String>) {
        require(args.firstOrNull() == "jdbc-benchmark") { "Expected jdbc-benchmark [--key=value]" }
        JdbcBenchmarkCommand().execute(CommandLineOptions(args.drop(1)))
    }
}
