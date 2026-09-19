package io.namastack.outbox

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Instance-local, bounded suppression of record keys this instance cannot currently process. */
internal class CompatibilityBackoff(
    private val clock: Clock,
    private val duration: Duration = Duration.ofSeconds(30),
    private val maximumSize: Int = 10_000,
) {
    private val deferredUntil = ConcurrentHashMap<String, Instant>()

    fun isDeferred(recordKey: String): Boolean {
        val until = deferredUntil[recordKey] ?: return false
        if (until.isAfter(clock.instant())) return true
        deferredUntil.remove(recordKey, until)
        return false
    }

    @Synchronized
    fun defer(recordKey: String) {
        val now = clock.instant()
        deferredUntil.entries.removeIf { !it.value.isAfter(now) }
        if (!deferredUntil.containsKey(recordKey) && deferredUntil.size >= maximumSize) {
            deferredUntil.keys.firstOrNull()?.let(deferredUntil::remove)
        }
        deferredUntil[recordKey] = now.plus(duration)
    }
}
