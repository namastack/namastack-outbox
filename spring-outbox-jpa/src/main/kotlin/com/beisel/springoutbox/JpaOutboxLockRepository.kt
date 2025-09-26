package com.beisel.springoutbox

import com.beisel.springoutbox.lock.OutboxLock
import com.beisel.springoutbox.lock.OutboxLockRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.interceptor.TransactionAspectSupport
import java.time.OffsetDateTime

internal open class JpaOutboxLockRepository(
    private val entityManager: EntityManager,
) : OutboxLockRepository {
    override fun findByAggregateId(aggregateId: String): OutboxLock? {
        val entity = findEntityByAggregateId(aggregateId) ?: return null

        return OutboxLockEntityMapper.map(entity)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun insertNew(lock: OutboxLock): OutboxLock? {
        // this prevents unnecessary sql error logs for duplicate key errors
        if (findEntityByAggregateId(lock.aggregateId) != null) {
            return null
        }

        return try {
            val entity = OutboxLockEntityMapper.map(lock)
            entityManager.persist(entity)
            entityManager.flush()
            OutboxLockEntityMapper.map(entity)
        } catch (_: PersistenceException) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()
            null
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun renew(
        aggregateId: String,
        expiresAt: OffsetDateTime,
    ): OutboxLock? =
        try {
            val entity = findEntityByAggregateId(aggregateId) ?: return null

            entity.expiresAt = expiresAt
            entityManager.flush()

            OutboxLockEntityMapper.map(entity)
        } catch (_: PersistenceException) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()
            null
        }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun deleteById(aggregateId: String) {
        entityManager
            .find(OutboxLockEntity::class.java, aggregateId)
            ?.let { entityManager.remove(it) }
    }

    private fun findEntityByAggregateId(aggregateId: String): OutboxLockEntity? =
        entityManager.find(OutboxLockEntity::class.java, aggregateId)
}
