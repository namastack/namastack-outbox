package com.beisel.springoutbox

import com.beisel.springoutbox.lock.OutboxLock
import com.beisel.springoutbox.lock.OutboxLockManager
import com.beisel.springoutbox.lock.OutboxLockRepository
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class OutboxLockManagerTest {
    private val lockRepository = mockk<OutboxLockRepository>()
    private val properties = mockk<OutboxProperties>()
    private val lockingProperties = mockk<OutboxProperties.Locking>()
    private val clock = Clock.fixed(Instant.parse("2025-09-25T10:00:00Z"), ZoneOffset.UTC)
    private val now = OffsetDateTime.now(clock)

    private lateinit var lockManager: OutboxLockManager

    @BeforeEach
    fun setup() {
        every { properties.locking } returns lockingProperties
        every { lockingProperties.extensionSeconds } returns 300L // 5 minutes
        every { lockingProperties.refreshThreshold } returns 60L // 1 minute

        lockManager = OutboxLockManager(lockRepository, properties, clock)
    }

    // ===== ACQUIRE TESTS =====

    @Test
    fun `acquire should return lock when insertNew succeeds`() {
        // given
        val aggregateId = "test-aggregate"
        val expectedLock = createLock(aggregateId, now, now.plusSeconds(300))
        every { lockRepository.insertNew(any()) } returns expectedLock

        // when
        val result = lockManager.acquire(aggregateId)

        // then
        assertThat(result).isEqualTo(expectedLock)
        verify {
            lockRepository.insertNew(
                match {
                    it.aggregateId == aggregateId && it.version == null
                },
            )
        }
    }

    @Test
    fun `acquire should attempt overtake when insertNew fails`() {
        // given
        val aggregateId = "test-aggregate"
        val expiredLock = createLock(aggregateId, now.minusMinutes(10), now.minusMinutes(5))
        val overtakenLock = createLock(aggregateId, now, now.plusSeconds(300))

        every { lockRepository.insertNew(any()) } returns null
        every { lockRepository.findByAggregateId(aggregateId) } returns expiredLock
        every { lockRepository.renew(aggregateId, any()) } returns overtakenLock

        // when
        val result = lockManager.acquire(aggregateId)

        // then
        assertThat(result).isEqualTo(overtakenLock)
        verify { lockRepository.insertNew(any()) }
        verify { lockRepository.findByAggregateId(aggregateId) }
        verify { lockRepository.renew(aggregateId, any()) }
    }

    @Test
    fun `acquire should return null when both insertNew and overtake fail`() {
        // given
        val aggregateId = "test-aggregate"
        every { lockRepository.insertNew(any()) } returns null
        every { lockRepository.findByAggregateId(aggregateId) } returns null

        // when
        val result = lockManager.acquire(aggregateId)

        // then
        assertThat(result).isNull()
        verify { lockRepository.insertNew(any()) }
        verify { lockRepository.findByAggregateId(aggregateId) }
        verify(exactly = 0) { lockRepository.renew(any(), any()) }
    }

    // ===== RELEASE TESTS =====

    @Test
    fun `release should call deleteById on repository`() {
        // given
        val aggregateId = "test-aggregate"
        every { lockRepository.deleteById(aggregateId) } just Runs

        // when
        lockManager.release(aggregateId)

        // then
        verify { lockRepository.deleteById(aggregateId) }
    }

    // ===== RENEW TESTS =====

    @Test
    fun `renew should return original lock when not expiring soon`() {
        // given
        val lock = createLock("test-aggregate", now, now.plusMinutes(10)) // expires in 10 minutes

        // when
        val result = lockManager.renew(lock)

        // then
        assertThat(result).isEqualTo(lock)
        verify(exactly = 0) { lockRepository.renew(any(), any()) }
    }

    @Test
    fun `renew should extend lock when expiring soon`() {
        // given
        val lock = createLock("test-aggregate", now, now.plusSeconds(59)) // expires in 59 seconds (< 60)
        val renewedLock = createLock("test-aggregate", now, now.plusSeconds(359)) // extended by 300s

        every { lockRepository.renew("test-aggregate", any()) } returns renewedLock

        // when
        val result = lockManager.renew(lock)

        // then
        assertThat(result).isEqualTo(renewedLock)
        verify {
            lockRepository.renew(
                "test-aggregate",
                match {
                    it.isAfter(now.plusSeconds(358)) && it.isBefore(now.plusSeconds(361))
                },
            )
        }
    }

    @Test
    fun `renew should not extend lock exactly at refresh threshold`() {
        // given - lock expires exactly at refresh threshold (60 seconds)
        val lock = createLock("test-aggregate", now, now.plusSeconds(60))

        // when
        val result = lockManager.renew(lock)

        // then
        assertThat(result).isEqualTo(lock) // should return original lock, no renewal
        verify(exactly = 0) { lockRepository.renew(any(), any()) }
    }

    @Test
    fun `renew should return null when repository renew fails`() {
        // given
        val lock = createLock("test-aggregate", now, now.plusSeconds(30)) // expires in 30 seconds
        every { lockRepository.renew(any(), any()) } returns null

        // when
        val result = lockManager.renew(lock)

        // then
        assertThat(result).isNull()
        verify { lockRepository.renew("test-aggregate", any()) }
    }

    // ===== OVERTAKE TESTS =====

    @Test
    fun `overtake should return null when lock does not exist`() {
        // given
        val aggregateId = "test-aggregate"
        every { lockRepository.findByAggregateId(aggregateId) } returns null

        // when
        val result = lockManager.overtake(aggregateId)

        // then
        assertThat(result).isNull()
        verify { lockRepository.findByAggregateId(aggregateId) }
        verify(exactly = 0) { lockRepository.renew(any(), any()) }
    }

    @Test
    fun `overtake should return null when lock is still active`() {
        // given
        val aggregateId = "test-aggregate"
        val activeLock = createLock(aggregateId, now, now.plusMinutes(5)) // still active
        every { lockRepository.findByAggregateId(aggregateId) } returns activeLock

        // when
        val result = lockManager.overtake(aggregateId)

        // then
        assertThat(result).isNull()
        verify { lockRepository.findByAggregateId(aggregateId) }
        verify(exactly = 0) { lockRepository.renew(any(), any()) }
    }

    @Test
    fun `overtake should renew expired lock successfully`() {
        // given
        val aggregateId = "test-aggregate"
        val expiredLock = createLock(aggregateId, now.minusMinutes(10), now.minusMinutes(5))
        val overtakenLock = createLock(aggregateId, now, now.plusSeconds(300))

        every { lockRepository.findByAggregateId(aggregateId) } returns expiredLock
        every { lockRepository.renew(aggregateId, any()) } returns overtakenLock

        // when
        val result = lockManager.overtake(aggregateId)

        // then
        assertThat(result).isEqualTo(overtakenLock)
        verify { lockRepository.findByAggregateId(aggregateId) }
        verify {
            lockRepository.renew(
                aggregateId,
                match {
                    it.isAfter(now.plusSeconds(299)) && it.isBefore(now.plusSeconds(301))
                },
            )
        }
    }

    @Test
    fun `overtake should return null when renew fails`() {
        // given
        val aggregateId = "test-aggregate"
        val expiredLock = createLock(aggregateId, now.minusMinutes(10), now.minusMinutes(5))

        every { lockRepository.findByAggregateId(aggregateId) } returns expiredLock
        every { lockRepository.renew(any(), any()) } returns null

        // when
        val result = lockManager.overtake(aggregateId)

        // then
        assertThat(result).isNull()
        verify { lockRepository.findByAggregateId(aggregateId) }
        verify { lockRepository.renew(aggregateId, any()) }
    }

    // ===== EDGE CASES =====

    @Test
    fun `overtake should handle lock that expired exactly one second ago`() {
        // given
        val aggregateId = "test-aggregate"
        val expiredLock = createLock(aggregateId, now.minusMinutes(5), now.minusSeconds(1)) // expired 1s ago
        val overtakenLock = createLock(aggregateId, now, now.plusSeconds(300))

        every { lockRepository.findByAggregateId(aggregateId) } returns expiredLock
        every { lockRepository.renew(aggregateId, any()) } returns overtakenLock

        // when
        val result = lockManager.overtake(aggregateId)

        // then
        assertThat(result).isEqualTo(overtakenLock)
        verify { lockRepository.findByAggregateId(aggregateId) }
        verify { lockRepository.renew(aggregateId, any()) }
    }

    @Test
    fun `renew should handle lock expiring in exactly one second less than threshold`() {
        // given - lock expires in 59 seconds (1 second less than 60s threshold)
        val lock = createLock("test-aggregate", now, now.plusSeconds(59))
        val renewedLock = createLock("test-aggregate", now, now.plusSeconds(359))

        every { lockRepository.renew("test-aggregate", any()) } returns renewedLock

        // when
        val result = lockManager.renew(lock)

        // then
        assertThat(result).isEqualTo(renewedLock)
        verify { lockRepository.renew("test-aggregate", any()) }
    }

    // ===== HELPER METHODS =====

    private fun createLock(
        aggregateId: String,
        acquiredAt: OffsetDateTime,
        expiresAt: OffsetDateTime,
        version: Long? = 1L,
    ): OutboxLock =
        OutboxLock(
            aggregateId = aggregateId,
            acquiredAt = acquiredAt,
            expiresAt = expiresAt,
            version = version,
        )
}
