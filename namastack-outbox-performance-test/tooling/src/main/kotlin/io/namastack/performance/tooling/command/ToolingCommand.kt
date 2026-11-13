package io.namastack.performance.tooling.command

import io.namastack.performance.tooling.ToolingServicesFactory

internal fun interface ToolingCommand {
    fun execute(options: CommandLineOptions)

    val name: String
        get() = javaClass.simpleName.removeSuffix("Command").replace(Regex("([a-z])([A-Z])"), "$1-$2").lowercase()
}

internal fun services(options: CommandLineOptions) = ToolingServicesFactory.create(options)
