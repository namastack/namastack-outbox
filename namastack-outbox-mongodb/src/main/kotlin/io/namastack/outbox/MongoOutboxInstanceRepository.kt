package io.namastack.outbox

import io.namastack.outbox.instance.OutboxInstance
import io.namastack.outbox.instance.OutboxInstanceRepository
import io.namastack.outbox.instance.OutboxInstanceStatus
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import java.time.Instant

/**
 * MongoDB implementation of the OutboxInstanceRepository interface.
 *
 * @author Stellar Hold
 * @since 1.5.0
 */
internal open class MongoOutboxInstanceRepository(
    private val mongoTemplate: MongoTemplate,
) : OutboxInstanceRepository {
    /**
     * Saves an outbox instance. Inserts or updates the instance document.
     *
     * @param instance the outbox instance to save
     * @return the saved outbox instance
     */
    override fun save(instance: OutboxInstance): OutboxInstance {
        val entity = MongoOutboxInstanceEntityMapper.map(instance)
        mongoTemplate.save(entity)

        return instance
    }

    /**
     * Finds an outbox instance by its ID.
     *
     * @param instanceId the instance ID to look up
     * @return the matching outbox instance, or null if not found
     */
    override fun findById(instanceId: String): OutboxInstance? =
        mongoTemplate
            .findById(instanceId, MongoOutboxInstanceEntity::class.java)
            ?.let { MongoOutboxInstanceEntityMapper.map(it) }

    /**
     * Finds all outbox instances ordered by creation time.
     *
     * @return list of all outbox instances
     */
    override fun findAll(): List<OutboxInstance> {
        val query = Query().with(Sort.by(Sort.Order.asc("createdAt")))

        return mongoTemplate
            .find(query, MongoOutboxInstanceEntity::class.java)
            .map { MongoOutboxInstanceEntityMapper.map(it) }
    }

    /**
     * Finds outbox instances by status, ordered by last heartbeat descending.
     *
     * @param status the instance status to filter by
     * @return list of matching outbox instances
     */
    override fun findByStatus(status: OutboxInstanceStatus): List<OutboxInstance> {
        val query =
            Query(Criteria.where("status").`is`(status))
                .with(Sort.by(Sort.Order.desc("lastHeartbeat")))

        return mongoTemplate
            .find(query, MongoOutboxInstanceEntity::class.java)
            .map { MongoOutboxInstanceEntityMapper.map(it) }
    }

    /**
     * Finds all active outbox instances.
     *
     * @return list of active outbox instances
     */
    override fun findActiveInstances(): List<OutboxInstance> = findByStatus(OutboxInstanceStatus.ACTIVE)

    /**
     * Finds instances with a stale heartbeat older than the given cutoff time.
     *
     * @param cutoffTime the heartbeat cutoff threshold
     * @return list of instances with stale heartbeats
     */
    override fun findInstancesWithStaleHeartbeat(cutoffTime: Instant): List<OutboxInstance> {
        val query =
            Query(
                Criteria
                    .where("lastHeartbeat")
                    .lt(cutoffTime)
                    .and("status")
                    .`in`(listOf(OutboxInstanceStatus.ACTIVE, OutboxInstanceStatus.SHUTTING_DOWN)),
            ).with(Sort.by(Sort.Order.asc("lastHeartbeat")))

        return mongoTemplate
            .find(query, MongoOutboxInstanceEntity::class.java)
            .map { MongoOutboxInstanceEntityMapper.map(it) }
    }

    /**
     * Updates the heartbeat timestamp for an instance.
     *
     * @param instanceId the instance ID to update
     * @param timestamp the new heartbeat timestamp
     * @return true if the instance was updated, false if not found
     */
    override fun updateHeartbeat(
        instanceId: String,
        timestamp: Instant,
    ): Boolean {
        val query = Query(Criteria.where("_id").`is`(instanceId))
        val update = Update().set("lastHeartbeat", timestamp).set("updatedAt", timestamp)
        val result = mongoTemplate.updateFirst(query, update, MongoOutboxInstanceEntity::class.java)

        return result.modifiedCount > 0
    }

    /**
     * Updates the status of an instance.
     *
     * @param instanceId the instance ID to update
     * @param status the new status
     * @param timestamp the update timestamp
     * @return true if the instance was updated, false if not found
     */
    override fun updateStatus(
        instanceId: String,
        status: OutboxInstanceStatus,
        timestamp: Instant,
    ): Boolean {
        val query = Query(Criteria.where("_id").`is`(instanceId))
        val update = Update().set("status", status).set("updatedAt", timestamp)
        val result = mongoTemplate.updateFirst(query, update, MongoOutboxInstanceEntity::class.java)

        return result.modifiedCount > 0
    }

    /**
     * Deletes an instance by its ID.
     *
     * @param instanceId the instance ID to delete
     * @return true if the instance was deleted, false if not found
     */
    override fun deleteById(instanceId: String): Boolean {
        val query = Query(Criteria.where("_id").`is`(instanceId))
        val result = mongoTemplate.remove(query, MongoOutboxInstanceEntity::class.java)

        return result.deletedCount > 0
    }

    /**
     * Counts instances by status.
     *
     * @param status the instance status to count
     * @return the number of instances with the given status
     */
    override fun countByStatus(status: OutboxInstanceStatus): Long {
        val query = Query(Criteria.where("status").`is`(status))

        return mongoTemplate.count(query, MongoOutboxInstanceEntity::class.java)
    }
}
