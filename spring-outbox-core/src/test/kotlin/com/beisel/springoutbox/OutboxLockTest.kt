package com.beisel.springoutbox

import com.beisel.springoutbox.lock.OutboxLock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class OutboxLockTest {
    private val clock = Clock.fixed(Instant.parse("2025-09-25T10:00:00Z"), ZoneOffset.UTC)
    private val now = OffsetDateTime.now(clock)

    @Test
    fun `create should create lock with correct properties`() {
        // given
        val aggregateId = "test-aggregate"
        val extensionSeconds = 300L

        // when
        val lock = OutboxLock.create(aggregateId, extensionSeconds, clock)

        // then
        assertThat(lock.aggregateId).isEqualTo(aggregateId)
        assertThat(lock.acquiredAt).isEqualTo(now)
        assertThat(lock.expiresAt).isEqualTo(now.plusSeconds(extensionSeconds))
        assertThat(lock.version).isNull()
    }

    @Test
    fun `isExpired should return true when lock is expired`() {
        // given - lock expired 1 minute ago
        val lock =
            OutboxLock(
                aggregateId = "test",
                acquiredAt = now.minusMinutes(10),
                expiresAt = now.minusMinutes(1),
                version = 1L,
            )

        // when
        val result = lock.isExpired(clock)

        // then
        assertThat(result).isTrue()
    }

    @Test
    fun `isExpired should return false when lock is not expired`() {
        // given - lock expires in 1 minute
        val lock =
            OutboxLock(
                aggregateId = "test",
                acquiredAt = now.minusMinutes(5),
                expiresAt = now.plusMinutes(1),
                version = 1L,
            )

        // when
        val result = lock.isExpired(clock)

        // then
        assertThat(result).isFalse()
    }

    @Test
    fun `isExpired should return false when lock expires exactly now`() {
        // given - lock expires exactly now
        val lock =
            OutboxLock(
                aggregateId = "test",
                acquiredAt = now.minusMinutes(5),
                expiresAt = now,
                version = 1L,
            )

        // when
        val result = lock.isExpired(clock)

        // then
        assertThat(result).isFalse() // isBefore returns false for equal times
    }

    @Test
    fun `isExpiringSoon should return true when lock expires within refresh threshold`() {
        // given - lock expires in 30 seconds, threshold is 60 seconds
        val refreshThreshold = 60L
        val lock =
            OutboxLock(
                aggregateId = "test",
                acquiredAt = now.minusMinutes(5),
                expiresAt = now.plusSeconds(30),
                version = 1L,
            )

        // when
        val result = lock.isExpiringSoon(refreshThreshold, clock)

        // then
        assertThat(result).isTrue()
    }

    @Test
    fun `isExpiringSoon should return false when lock expires after refresh threshold`() {
        // given - lock expires in 90 seconds, threshold is 60 seconds
        val refreshThreshold = 60L
        val lock =
            OutboxLock(
                aggregateId = "test",
                acquiredAt = now.minusMinutes(5),
                expiresAt = now.plusSeconds(90),
                version = 1L,
            )

        // when
        val result = lock.isExpiringSoon(refreshThreshold, clock)

        // then
        assertThat(result).isFalse()
    }

    @Test
    fun `isExpiringSoon should return false when lock expires exactly at refresh threshold`() {
        // given - lock expires in exactly 60 seconds, threshold is 60 seconds
        val refreshThreshold = 60L
        val lock =
            OutboxLock(
                aggregateId = "test",
                acquiredAt = now.minusMinutes(5),
                expiresAt = now.plusSeconds(60),
                version = 1L,
            )

        // when
        val result = lock.isExpiringSoon(refreshThreshold, clock)

        // then
        assertThat(result).isFalse() // isBefore returns false for equal times
    }

    @Test
    fun `isExpiringSoon should return true when lock expires exactly one second before threshold`() {
        // given - lock expires in 59 seconds, threshold is 60 seconds
        val refreshThreshold = 60L
        val lock =
            OutboxLock(
                aggregateId = "test",
                acquiredAt = now.minusMinutes(5),
                expiresAt = now.plusSeconds(59),
                version = 1L,
            )

        // when
        val result = lock.isExpiringSoon(refreshThreshold, clock)

        // then
        assertThat(result).isTrue()
    }

    @Test
    fun `isExpiringSoon should handle zero refresh threshold`() {
        // given - lock expires in 10 seconds, threshold is 0
        val refreshThreshold = 0L
        val lock =
            OutboxLock(
                aggregateId = "test",
                acquiredAt = now.minusMinutes(5),
                expiresAt = now.plusSeconds(10),
                version = 1L,
            )

        // when
        val result = lock.isExpiringSoon(refreshThreshold, clock)

        // then
        assertThat(result).isFalse() // 10 seconds in future minus 0 = 10 seconds in future
    }

    @Test
    fun `isExpiringSoon should handle large refresh threshold`() {
        // given - lock expires in 30 minutes, threshold is 60 minutes
        val refreshThreshold = 3600L // 60 minutes
        val lock =
            OutboxLock(
                aggregateId = "test",
                acquiredAt = now.minusMinutes(5),
                expiresAt = now.plusMinutes(30),
                version = 1L,
            )

        // when
        val result = lock.isExpiringSoon(refreshThreshold, clock)

        // then
        assertThat(result).isTrue() // 30 min - 60 min = -30 min (in past)
    }
}
