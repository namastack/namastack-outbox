package io.namastack.outbox

import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.aggregation.Aggregation
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import java.time.Instant

/**
 * MongoDB implementation of the OutboxRecordRepository and OutboxRecordStatusRepository interfaces.
 *
 * @author Stellar Hold
 * @since 1.1.0
 */
internal open class MongoOutboxRecordRepository(
    private val mongoTemplate: MongoTemplate,
    private val entityMapper: MongoOutboxRecordEntityMapper,
    private val clock: java.time.Clock,
) : OutboxRecordRepository,
    OutboxRecordStatusRepository {

    override fun <T> save(record: OutboxRecord<T>): OutboxRecord<T> {
        val entity = entityMapper.map(record)
        mongoTemplate.save(entity)
        return record
    }

    override fun findPendingRecords(): List<OutboxRecord<*>> = findRecordsByStatus(OutboxRecordStatus.NEW)

    override fun findCompletedRecords(): List<OutboxRecord<*>> = findRecordsByStatus(OutboxRecordStatus.COMPLETED)

    override fun findFailedRecords(): List<OutboxRecord<*>> = findRecordsByStatus(OutboxRecordStatus.FAILED)

    override fun findIncompleteRecordsByRecordKey(recordKey: String): List<OutboxRecord<*>> {
        val query = Query(
            Criteria.where("recordKey").`is`(recordKey)
                .and("status").`is`(OutboxRecordStatus.NEW)
        ).with(Sort.by(Sort.Order.asc("createdAt")))
        
        return mongoTemplate.find(query, MongoOutboxRecordEntity::class.java)
            .map { entityMapper.map(it) }
    }

    /**
     * Finds record keys in specified partitions that are ready for processing.
     *
     * This implementation uses an atomic aggregation pipeline to ensure strict FIFO
     * processing per recordKey. It identifies the oldest incomplete record for each
     * key and only returns the key if that specific oldest record is ready.
     */
    override fun findRecordKeysInPartitions(
        partitions: Set<Int>,
        status: OutboxRecordStatus,
        batchSize: Int,
        ignoreRecordKeysWithPreviousFailure: Boolean
    ): List<String> {
        val now = Instant.now(clock)

        val matchIncomplete = Aggregation.match(
            Criteria.where("partitionNo").`in`(partitions)
                .and("completedAt").`is`(null)
        )

        val sortOldestFirst = Aggregation.sort(
            Sort.by(
                Sort.Order.asc("recordKey"),
                Sort.Order.asc("createdAt")
            )
        )

        // Group by recordKey and take the absolute oldest record (the "blocker")
        val groupByRecordKey = Aggregation.group("recordKey")
            .first(Aggregation.ROOT).`as`("oldestDoc")

        // Only process if the oldest record for this key matches our target status and retry time
        // If the 'ignoreRecordKeysWithPreviousFailure' is false, it's simpler but we stick to the
        // atomic pipeline for safety and consistency.
        val filterReady = Aggregation.match(
            Criteria.where("oldestDoc.status").`is`(status.name)
                .and("oldestDoc.nextRetryAt").lte(now)
        )

        val finalAggregation = Aggregation.newAggregation(
            matchIncomplete,
            sortOldestFirst,
            groupByRecordKey,
            filterReady,
            Aggregation.sort(Sort.by(Sort.Order.asc("oldestDoc.createdAt"))),
            Aggregation.limit(batchSize.toLong()),
            Aggregation.project()
                .andExclude("_id")
                .and("_id").`as`("recordKey")
        )

        return mongoTemplate.aggregate(
            finalAggregation,
            MongoOutboxRecordEntity.COLLECTION_NAME,
            RecordKeyDto::class.java
        ).mappedResults.map { it.recordKey }
    }

    override fun countByStatus(status: OutboxRecordStatus): Long {
        val query = Query(Criteria.where("status").`is`(status))
        return mongoTemplate.count(query, MongoOutboxRecordEntity::class.java)
    }

    override fun countRecordsByPartition(partition: Int, status: OutboxRecordStatus): Long {
        val query = Query(
            Criteria.where("partitionNo").`is`(partition)
                .and("status").`is`(status)
        )
        return mongoTemplate.count(query, MongoOutboxRecordEntity::class.java)
    }

    override fun deleteByStatus(status: OutboxRecordStatus) {
        val query = Query(Criteria.where("status").`is`(status))
        mongoTemplate.remove(query, MongoOutboxRecordEntity::class.java)
    }

    override fun deleteByRecordKeyAndStatus(recordKey: String, status: OutboxRecordStatus) {
        val query = Query(
            Criteria.where("recordKey").`is`(recordKey)
                .and("status").`is`(status)
        )
        mongoTemplate.remove(query, MongoOutboxRecordEntity::class.java)
    }

    override fun deleteById(id: String) {
        val query = Query(Criteria.where("id").`is`(id))
        mongoTemplate.remove(query, MongoOutboxRecordEntity::class.java)
    }

    private fun findRecordsByStatus(status: OutboxRecordStatus): List<OutboxRecord<*>> {
        val query = Query(Criteria.where("status").`is`(status))
            .with(Sort.by(Sort.Order.asc("createdAt")))
        return mongoTemplate.find(query, MongoOutboxRecordEntity::class.java)
            .map { entityMapper.map(it) }
    }
}

/**
 * Result DTO for the aggregation pipeline.
 * Extracts the recordKey field from the project stage.
 */
internal data class RecordKeyDto(
    val recordKey: String = "",
)
