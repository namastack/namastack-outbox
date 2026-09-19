package io.namastack.outbox

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class CompatibilityBackoffTest {
    @Test
    fun `deferred key becomes eligible after instance-local delay`() {
        val clock = MutableClock(Instant.parse("2024-01-01T00:00:00Z"))
        val backoff = CompatibilityBackoff(clock, Duration.ofSeconds(30))

        backoff.defer("key")
        assertThat(backoff.isDeferred("key")).isTrue()

        clock.instant = clock.instant.plusSeconds(30)
        assertThat(backoff.isDeferred("key")).isFalse()
    }

    @Test
    fun `cache stays bounded`() {
        val clock = MutableClock(Instant.parse("2024-01-01T00:00:00Z"))
        val backoff = CompatibilityBackoff(clock, maximumSize = 1)

        backoff.defer("first")
        backoff.defer("second")

        assertThat(listOf("first", "second").count(backoff::isDeferred)).isEqualTo(1)
    }

    private class MutableClock(
        var instant: Instant,
    ) : Clock() {
        override fun instant(): Instant = instant

        override fun getZone(): ZoneId = ZoneId.of("UTC")

        override fun withZone(zone: ZoneId): Clock = this
    }
}
