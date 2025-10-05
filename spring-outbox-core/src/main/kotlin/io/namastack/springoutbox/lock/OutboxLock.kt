package io.namastack.springoutbox.lock

import java.time.Clock
import java.time.OffsetDateTime

data class OutboxLock(
    val aggregateId: String,
    val acquiredAt: OffsetDateTime,
    val expiresAt: OffsetDateTime,
    val version: Long?,
) {
    companion object {
        fun create(
            aggregateId: String,
            extensionSeconds: Long,
            clock: Clock,
        ): OutboxLock {
            val now = OffsetDateTime.now(clock)
            val expiresAt = now.plusSeconds(extensionSeconds)

            return OutboxLock(aggregateId, now, expiresAt, null)
        }
    }

    fun isExpired(clock: Clock): Boolean = expiresAt.isBefore(OffsetDateTime.now(clock))

    fun isExpiringSoon(
        refreshThreshold: Long,
        clock: Clock,
    ): Boolean = expiresAt.minusSeconds(refreshThreshold).isBefore(OffsetDateTime.now(clock))
}
