package com.beisel.springoutbox

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

class OutboxLockEntityMapperTest {
    @Test
    fun `should map OutboxLock to OutboxLockEntity with all properties`() {
        // given
        val now = OffsetDateTime.now()
        val expiresAt = now.plusMinutes(5)

        val lock =
            OutboxLock(
                aggregateId = "test-aggregate-123",
                acquiredAt = now,
                expiresAt = expiresAt,
                version = 1L,
            )

        // when
        val entity = OutboxLockEntityMapper.map(lock)

        // then
        assertThat(entity.aggregateId).isEqualTo("test-aggregate-123")
        assertThat(entity.acquiredAt).isEqualTo(now)
        assertThat(entity.expiresAt).isEqualTo(expiresAt)
        assertThat(entity.version).isEqualTo(1L)
    }

    @Test
    fun `should map OutboxLock to OutboxLockEntity with null version`() {
        // given
        val now = OffsetDateTime.now()
        val expiresAt = now.plusMinutes(10)

        val lock =
            OutboxLock(
                aggregateId = "new-aggregate",
                acquiredAt = now,
                expiresAt = expiresAt,
                version = null,
            )

        // when
        val entity = OutboxLockEntityMapper.map(lock)

        // then
        assertThat(entity.aggregateId).isEqualTo("new-aggregate")
        assertThat(entity.acquiredAt).isEqualTo(now)
        assertThat(entity.expiresAt).isEqualTo(expiresAt)
        assertThat(entity.version).isNull()
    }

    @Test
    fun `should map OutboxLock with version zero`() {
        // given
        val now = OffsetDateTime.now()

        val lock =
            OutboxLock(
                aggregateId = "version-zero-aggregate",
                acquiredAt = now,
                expiresAt = now.plusSeconds(300),
                version = 0L,
            )

        // when
        val entity = OutboxLockEntityMapper.map(lock)

        // then
        assertThat(entity.aggregateId).isEqualTo("version-zero-aggregate")
        assertThat(entity.version).isEqualTo(0L)
    }

    @Test
    fun `should map OutboxLockEntity to OutboxLock with all properties`() {
        // given
        val now = OffsetDateTime.now()
        val expiresAt = now.plusMinutes(3)

        val entity =
            OutboxLockEntity(
                aggregateId = "entity-aggregate-456",
                acquiredAt = now,
                expiresAt = expiresAt,
                version = 5L,
            )

        // when
        val lock = OutboxLockEntityMapper.map(entity)

        // then
        assertThat(lock.aggregateId).isEqualTo("entity-aggregate-456")
        assertThat(lock.acquiredAt).isEqualTo(now)
        assertThat(lock.expiresAt).isEqualTo(expiresAt)
        assertThat(lock.version).isEqualTo(5L)
    }

    @Test
    fun `should map OutboxLockEntity to OutboxLock with null version`() {
        // given
        val now = OffsetDateTime.now()
        val expiresAt = now.plusHours(1)

        val entity =
            OutboxLockEntity(
                aggregateId = "null-version-aggregate",
                acquiredAt = now,
                expiresAt = expiresAt,
                version = null,
            )

        // when
        val lock = OutboxLockEntityMapper.map(entity)

        // then
        assertThat(lock.aggregateId).isEqualTo("null-version-aggregate")
        assertThat(lock.acquiredAt).isEqualTo(now)
        assertThat(lock.expiresAt).isEqualTo(expiresAt)
        assertThat(lock.version).isNull()
    }

    @Test
    fun `should maintain data integrity in round-trip mapping`() {
        // given - original lock
        val originalLock =
            OutboxLock(
                aggregateId = "round-trip-aggregate",
                acquiredAt = OffsetDateTime.now(),
                expiresAt = OffsetDateTime.now().plusMinutes(10),
                version = 3L,
            )

        // when - round trip: Lock -> Entity -> Lock
        val entity = OutboxLockEntityMapper.map(originalLock)
        val mappedBackLock = OutboxLockEntityMapper.map(entity)

        // then - all properties should be identical
        assertThat(mappedBackLock.aggregateId).isEqualTo(originalLock.aggregateId)
        assertThat(mappedBackLock.acquiredAt).isEqualTo(originalLock.acquiredAt)
        assertThat(mappedBackLock.expiresAt).isEqualTo(originalLock.expiresAt)
        assertThat(mappedBackLock.version).isEqualTo(originalLock.version)
    }

    @Test
    fun `should handle round-trip mapping with null version`() {
        // given - original lock with null version
        val originalLock =
            OutboxLock(
                aggregateId = "null-version-round-trip",
                acquiredAt = OffsetDateTime.now(),
                expiresAt = OffsetDateTime.now().plusMinutes(5),
                version = null,
            )

        // when - round trip: Lock -> Entity -> Lock
        val entity = OutboxLockEntityMapper.map(originalLock)
        val mappedBackLock = OutboxLockEntityMapper.map(entity)

        // then - all properties should be identical
        assertThat(mappedBackLock.aggregateId).isEqualTo(originalLock.aggregateId)
        assertThat(mappedBackLock.acquiredAt).isEqualTo(originalLock.acquiredAt)
        assertThat(mappedBackLock.expiresAt).isEqualTo(originalLock.expiresAt)
        assertThat(mappedBackLock.version).isEqualTo(originalLock.version)
    }

    @Test
    fun `should handle long aggregate IDs`() {
        // given
        val longAggregateId = "very-long-aggregate-id-" + "x".repeat(100)
        val now = OffsetDateTime.now()

        val lock =
            OutboxLock(
                aggregateId = longAggregateId,
                acquiredAt = now,
                expiresAt = now.plusMinutes(15),
                version = 1L,
            )

        // when
        val entity = OutboxLockEntityMapper.map(lock)
        val mappedBack = OutboxLockEntityMapper.map(entity)

        // then
        assertThat(entity.aggregateId).isEqualTo(longAggregateId)
        assertThat(mappedBack.aggregateId).isEqualTo(longAggregateId)
    }

    @Test
    fun `should handle special characters in aggregate ID`() {
        // given
        val specialAggregateId = "aggregate-with-äöüß-and-🚀-special-chars"
        val now = OffsetDateTime.now()

        val lock =
            OutboxLock(
                aggregateId = specialAggregateId,
                acquiredAt = now,
                expiresAt = now.plusMinutes(20),
                version = 2L,
            )

        // when
        val entity = OutboxLockEntityMapper.map(lock)
        val mappedBack = OutboxLockEntityMapper.map(entity)

        // then
        assertThat(entity.aggregateId).isEqualTo(specialAggregateId)
        assertThat(mappedBack.aggregateId).isEqualTo(specialAggregateId)
    }

    @Test
    fun `should handle UUID-based aggregate IDs`() {
        // given
        val uuidAggregateId = UUID.randomUUID().toString()
        val now = OffsetDateTime.now()

        val lock =
            OutboxLock(
                aggregateId = uuidAggregateId,
                acquiredAt = now,
                expiresAt = now.plusSeconds(600),
                version = 10L,
            )

        // when
        val entity = OutboxLockEntityMapper.map(lock)
        val mappedBack = OutboxLockEntityMapper.map(entity)

        // then
        assertThat(entity.aggregateId).isEqualTo(uuidAggregateId)
        assertThat(mappedBack.aggregateId).isEqualTo(uuidAggregateId)
        assertThat(entity.version).isEqualTo(10L)
        assertThat(mappedBack.version).isEqualTo(10L)
    }

    @Test
    fun `should handle very large version numbers`() {
        // given
        val largeVersion = Long.MAX_VALUE
        val now = OffsetDateTime.now()

        val lock =
            OutboxLock(
                aggregateId = "large-version-test",
                acquiredAt = now,
                expiresAt = now.plusMinutes(25),
                version = largeVersion,
            )

        // when
        val entity = OutboxLockEntityMapper.map(lock)
        val mappedBack = OutboxLockEntityMapper.map(entity)

        // then
        assertThat(entity.version).isEqualTo(largeVersion)
        assertThat(mappedBack.version).isEqualTo(largeVersion)
    }

    @Test
    fun `should handle precise timestamp mapping`() {
        // given - timestamps with nanosecond precision
        val preciseAcquiredAt = OffsetDateTime.parse("2025-09-25T10:15:30.123456789Z")
        val preciseExpiresAt = OffsetDateTime.parse("2025-09-25T10:20:30.987654321Z")

        val lock =
            OutboxLock(
                aggregateId = "precise-timestamp-test",
                acquiredAt = preciseAcquiredAt,
                expiresAt = preciseExpiresAt,
                version = 1L,
            )

        // when
        val entity = OutboxLockEntityMapper.map(lock)
        val mappedBack = OutboxLockEntityMapper.map(entity)

        // then
        assertThat(entity.acquiredAt).isEqualTo(preciseAcquiredAt)
        assertThat(entity.expiresAt).isEqualTo(preciseExpiresAt)
        assertThat(mappedBack.acquiredAt).isEqualTo(preciseAcquiredAt)
        assertThat(mappedBack.expiresAt).isEqualTo(preciseExpiresAt)
    }
}
