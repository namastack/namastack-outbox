package com.beisel.springoutbox

import com.beisel.springoutbox.lock.OutboxLock

object OutboxLockEntityMapper {
    fun map(lock: OutboxLock): OutboxLockEntity =
        OutboxLockEntity(
            aggregateId = lock.aggregateId,
            acquiredAt = lock.acquiredAt,
            expiresAt = lock.expiresAt,
            version = lock.version,
        )

    fun map(entity: OutboxLockEntity): OutboxLock =
        OutboxLock(
            aggregateId = entity.aggregateId,
            acquiredAt = entity.acquiredAt,
            expiresAt = entity.expiresAt,
            version = entity.version,
        )
}
