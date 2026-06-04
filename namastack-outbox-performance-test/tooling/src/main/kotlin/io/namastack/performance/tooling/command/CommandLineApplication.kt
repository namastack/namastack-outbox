package io.namastack.performance.tooling.command

internal class CommandLineApplication {
    fun run(args: Array<String>) {
        require(args.isNotEmpty()) { usage() }
        val options = CommandLineOptions(args.drop(1))
        val command =
            commands[args.first()]
                ?: error("Unknown command '${args.first()}'.\n${usage()}")
        command.execute(options)
    }

    private val commands =
        listOf(
            SeedCommand(),
            TriggerCommand(),
            ProduceCommand(),
            AwaitCommand(),
            AwaitClusterCommand(),
            ResetOutboxCommand(),
            CollectDrainCommand(),
            CollectSteadyStateCommand(),
        ).associateBy(ToolingCommand::name)

    private fun usage() =
        """
        Usage: tooling <command> [--key=value]

          seed                 Stage deterministic records efficiently with PostgreSQL COPY.
          trigger              Insert staged records into outbox_record for a backlog-drain run.
          produce              Continuously write an exact number of records at a target rate.
          await                Wait for all records to reach a terminal state.
          await-cluster        Wait for active consumers and all 256 partition assignments.
          reset-outbox         Clear records while preserving the running consumer cluster.
          collect-drain        Collect and report an existing-backlog recovery run.
          collect-steady-state Collect and report a continuous producer run plus its drain phase.
        """.trimIndent()
}
