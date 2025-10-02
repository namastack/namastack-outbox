package com.beisel.springoutbox

import com.beisel.springoutbox.lock.OutboxLock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

@DataJpaTest
@ImportAutoConfiguration(JpaOutboxAutoConfiguration::class)
class JpaOutboxLockRepositoryTest {
    private val clock: Clock = Clock.systemDefaultZone()

    @Autowired
    private lateinit var jpaOutboxLockRepository: JpaOutboxLockRepository

    @Test
    fun `saves a lock entity`() {
        val aggregateId = UUID.randomUUID().toString()
        val now = OffsetDateTime.now(clock)
        val lock =
            OutboxLock(
                aggregateId = aggregateId,
                acquiredAt = now,
                expiresAt = now.plusMinutes(5),
                version = null,
            )
        jpaOutboxLockRepository.insertNew(lock)
        val persistedLock = jpaOutboxLockRepository.findByAggregateId(aggregateId)
        println(lock)
        println(persistedLock)

        assertThat(persistedLock?.aggregateId).isEqualTo(lock.aggregateId)
        assertThat(persistedLock?.acquiredAt).isEqualTo(lock.acquiredAt)
        assertThat(persistedLock?.expiresAt).isEqualTo(lock.expiresAt)
        assertThat(persistedLock?.version).isEqualTo(0L)
    }

    @Test
    fun `renews a lock entity`() {
        val aggregateId = UUID.randomUUID().toString()
        val now = OffsetDateTime.now(clock)
        val newExpiresAt = now.plusMinutes(10)
        val lock =
            OutboxLock(
                aggregateId = aggregateId,
                acquiredAt = now,
                expiresAt = now,
                version = null,
            )
        jpaOutboxLockRepository.insertNew(lock)
        jpaOutboxLockRepository.renew(aggregateId, newExpiresAt)

        val persistedUpdatedLock = jpaOutboxLockRepository.findByAggregateId(aggregateId) ?: throw AssertionError()
        assertThat(persistedUpdatedLock.expiresAt).isEqualTo(newExpiresAt)
    }

    @Test
    fun `finds lock by aggregate id`() {
        val aggregateId = UUID.randomUUID().toString()
        val now = OffsetDateTime.now(clock)
        val lock =
            OutboxLock(
                aggregateId = aggregateId,
                acquiredAt = now,
                expiresAt = now,
                version = null,
            )
        jpaOutboxLockRepository.insertNew(lock)
        val foundLock = jpaOutboxLockRepository.findByAggregateId(aggregateId)
        assertThat(foundLock).isNotNull
        assertThat(foundLock?.aggregateId).isEqualTo(aggregateId)
    }

    @Test
    fun `deletes lock by aggregate id`() {
        val aggregateId = UUID.randomUUID().toString()
        val now = OffsetDateTime.now(clock)
        val lock =
            OutboxLock(
                aggregateId = aggregateId,
                acquiredAt = now,
                expiresAt = now.plusMinutes(5),
                version = 1L,
            )
        jpaOutboxLockRepository.insertNew(lock)
        jpaOutboxLockRepository.deleteById(aggregateId)
        val foundLock = jpaOutboxLockRepository.findByAggregateId(aggregateId)
        assertThat(foundLock).isNull()
    }

    @EnableOutbox
    @SpringBootApplication
    class TestApplication
}
