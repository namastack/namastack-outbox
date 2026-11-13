package io.namastack.performance.tooling.command

import io.namastack.performance.tooling.internal.parseDuration
import java.time.Duration

internal class CommandLineOptions(args: List<String>) {
    private val values =
        args.associate { argument ->
            require(argument.startsWith("--") && argument.contains("=")) { "Expected --key=value but got '$argument'" }
            val (key, value) = argument.removePrefix("--").split("=", limit = 2)
            key to value
        }

    fun string(
        name: String,
        default: String? = null,
    ): String = values[name] ?: default ?: error("Missing required option --$name")

    fun int(
        name: String,
        default: Int? = null,
    ) = string(name, default?.toString()).toInt()

    fun long(
        name: String,
        default: Long? = null,
    ) = string(name, default?.toString()).toLong()

    fun double(
        name: String,
        default: Double? = null,
    ) = string(name, default?.toString()).toDouble()

    fun duration(
        name: String,
        default: Duration,
    ) = parseDuration(string(name, default.toString()))
}
