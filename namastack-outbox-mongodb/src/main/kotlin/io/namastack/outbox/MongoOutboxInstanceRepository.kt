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
 * @since 1.1.0
 */
internal open class MongoOutboxInstanceRepository(
    private val mongoTemplate: MongoTemplate,
) : OutboxInstanceRepository {

    override fun save(instance: OutboxInstance): OutboxInstance {
        val entity = MongoOutboxInstanceEntityMapper.map(instance)
        mongoTemplate.save(entity)
        return instance
    }

    override fun findById(instanceId: String): OutboxInstance? {
        return mongoTemplate.findById(instanceId, MongoOutboxInstanceEntity::class.java)
            ?.let { MongoOutboxInstanceEntityMapper.map(it) }
    }

    override fun findAll(): List<OutboxInstance> {
        val query = Query().with(Sort.by(Sort.Order.asc("createdAt")))
        return mongoTemplate.find(query, MongoOutboxInstanceEntity::class.java)
            .map { MongoOutboxInstanceEntityMapper.map(it) }
    }

    override fun findByStatus(status: OutboxInstanceStatus): List<OutboxInstance> {
        val query = Query(Criteria.where("status").`is`(status))
            .with(Sort.by(Sort.Order.desc("lastHeartbeat")))
        return mongoTemplate.find(query, MongoOutboxInstanceEntity::class.java)
            .map { MongoOutboxInstanceEntityMapper.map(it) }
    }

    override fun findActiveInstances(): List<OutboxInstance> = findByStatus(OutboxInstanceStatus.ACTIVE)

    override fun findInstancesWithStaleHeartbeat(cutoffTime: Instant): List<OutboxInstance> {
        val query = Query(
            Criteria.where("lastHeartbeat").lt(cutoffTime)
                .and("status").`in`(listOf(OutboxInstanceStatus.ACTIVE, OutboxInstanceStatus.SHUTTING_DOWN))
        ).with(Sort.by(Sort.Order.asc("lastHeartbeat")))
        
        return mongoTemplate.find(query, MongoOutboxInstanceEntity::class.java)
            .map { MongoOutboxInstanceEntityMapper.map(it) }
    }

    override fun updateHeartbeat(instanceId: String, timestamp: Instant): Boolean {
        val query = Query(Criteria.where("instanceId").`is`(instanceId))
        val update = Update().set("lastHeartbeat", timestamp).set("updatedAt", timestamp)
        val result = mongoTemplate.updateFirst(query, update, MongoOutboxInstanceEntity::class.java)
        return result.modifiedCount > 0
    }

    override fun updateStatus(instanceId: String, status: OutboxInstanceStatus, timestamp: Instant): Boolean {
        val query = Query(Criteria.where("instanceId").`is`(instanceId))
        val update = Update().set("status", status).set("updatedAt", timestamp)
        val result = mongoTemplate.updateFirst(query, update, MongoOutboxInstanceEntity::class.java)
        return result.modifiedCount > 0
    }

    override fun deleteById(instanceId: String): Boolean {
        val query = Query(Criteria.where("instanceId").`is`(instanceId))
        val result = mongoTemplate.remove(query, MongoOutboxInstanceEntity::class.java)
        return result.deletedCount > 0
    }

    override fun countByStatus(status: OutboxInstanceStatus): Long {
        val query = Query(Criteria.where("status").`is`(status))
        return mongoTemplate.count(query, MongoOutboxInstanceEntity::class.java)
    }
}
