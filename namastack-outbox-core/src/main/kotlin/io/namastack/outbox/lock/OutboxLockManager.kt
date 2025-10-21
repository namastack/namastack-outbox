package io.namastack.outbox.lock

import io.namastack.outbox.OutboxProperties
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.OffsetDateTime

class OutboxLockManager(
    private val lockRepository: OutboxLockRepository,
    private val properties: OutboxProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(OutboxLockManager::class.java)

    fun acquire(aggregateId: String): OutboxLock? {
        val lock =
            OutboxLock.create(
                aggregateId = aggregateId,
                extensionSeconds = properties.locking.extensionSeconds,
                clock = clock,
            )

        return when (val acquiredLock = lockRepository.insertNew(lock)) {
            null -> {
                log.debug("❌ Failed to acquire outbox lock for {}", aggregateId)
                overtake(aggregateId)
            }

            else -> {
                log.debug("✅ Acquired lock for {}", aggregateId)
                acquiredLock
            }
        }
    }

    fun release(aggregateId: String) {
        lockRepository.deleteById(aggregateId)
        log.debug("✅ Released lock for {}", aggregateId)
    }

    fun renew(lock: OutboxLock): OutboxLock? {
        if (!lock.isExpiringSoon(properties.locking.refreshThreshold, clock)) return lock

        val aggregateId = lock.aggregateId
        val newExpirationTime = lock.expiresAt.plusSeconds(properties.locking.extensionSeconds)
        val renewedLock = lockRepository.renew(aggregateId, newExpirationTime)

        when (renewedLock) {
            null -> log.debug("❌ Failed to renew outbox lock for {}", aggregateId)
            else -> log.debug("✅ Renewed lock for {}", aggregateId)
        }

        return renewedLock
    }

    fun overtake(aggregateId: String): OutboxLock? {
        val existingLock =
            lockRepository.findByAggregateId(aggregateId)
                ?: return null.also {
                    log.debug("❌ Cannot overtake lock for {}, because it does not exist.", aggregateId)
                }

        if (!existingLock.isExpired(clock)) {
            return null.also {
                log.debug("❌ Cannot overtake lock for {}, because it's still active.", aggregateId)
            }
        }

        val newExpirationTime = OffsetDateTime.now(clock).plusSeconds(properties.locking.extensionSeconds)
        val overtakenLock = lockRepository.renew(aggregateId, newExpirationTime)

        when (overtakenLock) {
            null -> log.debug("❌ Failed to overtake expired lock for {}", aggregateId)
            else -> log.debug("✅ Successfully overtook expired lock for {}", aggregateId)
        }

        return overtakenLock
    }
}
