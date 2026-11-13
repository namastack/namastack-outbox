package io.namastack.performance.tooling.internal

import java.time.Duration

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
