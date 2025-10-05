package io.namastack.springoutbox.lock

import java.time.OffsetDateTime

interface OutboxLockRepository {
    fun findByAggregateId(aggregateId: String): OutboxLock?

    fun insertNew(lock: OutboxLock): OutboxLock?

    fun renew(
        aggregateId: String,
        expiresAt: OffsetDateTime,
    ): OutboxLock?

    fun deleteById(aggregateId: String)
}
