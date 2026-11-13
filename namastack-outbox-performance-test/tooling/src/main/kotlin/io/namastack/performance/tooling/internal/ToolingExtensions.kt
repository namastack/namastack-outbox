package io.namastack.performance.tooling.internal

import tools.jackson.databind.json.JsonMapper
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.Locale

internal val jsonMapper = JsonMapper.builder().build()

internal fun sleepUntil(deadlineNanos: Long) {
    while (true) {
        val remaining = deadlineNanos - System.nanoTime()
        if (remaining <= 0) return
        Thread.sleep(remaining / 1_000_000, (remaining % 1_000_000).toInt())
    }
}

internal fun copyValue(value: String?) = value?.replace("\\", "\\\\")?.replace("\t", "\\t")?.replace("\n", "\\n") ?: "\\N"

internal fun escapeLike(value: String) = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

internal fun ceilDiv(
    dividend: Long,
    divisor: Long,
) = (dividend + divisor - 1) / divisor

internal fun elapsedMillis(startedAt: Long) = (System.nanoTime() - startedAt) / 1_000_000

internal fun secondsBetween(
    start: Instant,
    end: Instant,
) = Duration.between(start, end).toNanos() / 1_000_000_000.0

internal fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)

internal fun format(value: Double) = String.format(Locale.US, "%.2f", value)

internal fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

internal fun parseDuration(value: String): Duration {
    if (value.startsWith("P")) return Duration.parse(value)
    val match = Regex("""(\d+)(ms|s|m|h)""").matchEntire(value) ?: error("Unsupported duration '$value'")
    val amount = match.groupValues[1].toLong()
    return when (match.groupValues[2]) {
        "ms" -> Duration.ofMillis(amount)
        "s" -> Duration.ofSeconds(amount)
        "m" -> Duration.ofMinutes(amount)
        "h" -> Duration.ofHours(amount)
        else -> error("Unsupported duration '$value'")
    }
}

internal fun commandOutput(vararg command: String): String? =
    try {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (process.waitFor() == 0) output else null
    } catch (_: Exception) {
        null
    }
