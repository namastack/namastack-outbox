package io.namastack.outbox

import io.namastack.outbox.lock.OutboxLock
import io.namastack.outbox.lock.OutboxLockRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime

internal open class JpaOutboxLockRepository(
    private val entityManager: EntityManager,
    private val transactionTemplate: TransactionTemplate,
) : OutboxLockRepository {
    private val newTransactionTemplate =
        TransactionTemplate(transactionTemplate.transactionManager!!).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    override fun findByAggregateId(aggregateId: String): OutboxLock? {
        val entity = findEntityByAggregateId(aggregateId) ?: return null

        return OutboxLockEntityMapper.map(entity)
    }

    override fun insertNew(lock: OutboxLock): OutboxLock? =
        newTransactionTemplate.execute { status ->
            // this prevents unnecessary sql error logs for duplicate key errors
            if (findEntityByAggregateId(lock.aggregateId) != null) {
                return@execute null
            }

            try {
                val entity = OutboxLockEntityMapper.map(lock)
                entityManager.persist(entity)
                entityManager.flush()
                OutboxLockEntityMapper.map(entity)
            } catch (_: PersistenceException) {
                status.setRollbackOnly()
                null
            }
        }

    override fun renew(
        aggregateId: String,
        expiresAt: OffsetDateTime,
    ): OutboxLock? =
        newTransactionTemplate.execute { status ->
            try {
                val entity = findEntityByAggregateId(aggregateId) ?: return@execute null

                entity.expiresAt = expiresAt
                entityManager.flush()

                OutboxLockEntityMapper.map(entity)
            } catch (_: PersistenceException) {
                status.setRollbackOnly()
                null
            }
        }

    override fun deleteById(aggregateId: String) {
        newTransactionTemplate.executeWithoutResult {
            entityManager
                .find(OutboxLockEntity::class.java, aggregateId)
                ?.let { entityManager.remove(it) }
        }
    }

    private fun findEntityByAggregateId(aggregateId: String): OutboxLockEntity? =
        entityManager.find(OutboxLockEntity::class.java, aggregateId)
}
