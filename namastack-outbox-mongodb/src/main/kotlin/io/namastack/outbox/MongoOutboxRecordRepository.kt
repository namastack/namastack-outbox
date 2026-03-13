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
     * When [ignoreRecordKeysWithPreviousFailure] is false, uses a single aggregation
     * pipeline for atomic execution.
     *
     * When [ignoreRecordKeysWithPreviousFailure] is true, uses a two-step approach:
     * 1. Find candidate records via aggregation pipeline
     * 2. For each candidate, check if older incomplete records exist
     *
     * Note: The two-step approach has a small theoretic race window between steps.
     * In practice, the partition system ensures only one instance processes each
     * partition at a time, eliminating concurrent access during normal operation.
     * Race conditions can only occur during the brief rebalancing window when an
     * instance fails.
     */
    override fun findRecordKeysInPartitions(
        partitions: Set<Int>,
        status: OutboxRecordStatus,
        batchSize: Int,
        ignoreRecordKeysWithPreviousFailure: Boolean
    ): List<String> {
        val now = Instant.now()

        if (!ignoreRecordKeysWithPreviousFailure) {
            return findRecordKeysSimple(partitions, status, now, batchSize)
        }

        return findRecordKeysWithFailureFilter(partitions, status, now, batchSize)
    }

    /**
     * Simple query using aggregation pipeline: find distinct record keys in partitions,
     * ordered by oldest createdAt. This is a single atomic database operation.
     */
    private fun findRecordKeysSimple(
        partitions: Set<Int>,
        status: OutboxRecordStatus,
        now: Instant,
        batchSize: Int
    ): List<String> {
        val aggregation = Aggregation.newAggregation(
            Aggregation.match(
                Criteria.where("partitionNo").`in`(partitions)
                    .and("status").`is`(status)
                    .and("nextRetryAt").lte(now)
            ),
            Aggregation.sort(Sort.by(Sort.Order.asc("createdAt"))),
            Aggregation.group("recordKey")
                .first("createdAt").`as`("minCreatedAt"),
            Aggregation.sort(Sort.by(Sort.Order.asc("minCreatedAt"))),
            Aggregation.limit(batchSize.toLong()),
        )

        return mongoTemplate.aggregate(
            aggregation,
            MongoOutboxRecordEntity.COLLECTION_NAME,
            RecordKeyResult::class.java
        ).mappedResults.map { it.id }
    }

    /**
     * Advanced query: find record keys, filtering out keys with older incomplete records.
     *
     * Uses aggregation to find candidates, then checks for blockers.
     * The partition ownership model ensures this is safe under normal operation.
     */
    private fun findRecordKeysWithFailureFilter(
        partitions: Set<Int>,
        status: OutboxRecordStatus,
        now: Instant,
        batchSize: Int
    ): List<String> {
        val criteria = Criteria.where("partitionNo").`in`(partitions)
            .and("status").`is`(status)
            .and("nextRetryAt").lte(now)

        val query = Query(criteria)
            .with(Sort.by(Sort.Order.asc("createdAt")))
            .limit(batchSize * 5)

        val records = mongoTemplate.find(query, MongoOutboxRecordEntity::class.java)

        return records.filter { record ->
            val olderIncompleteQuery = Query(
                Criteria.where("recordKey").`is`(record.recordKey)
                    .and("completedAt").`is`(null)
                    .and("createdAt").lt(record.createdAt)
            ).limit(1)
            !mongoTemplate.exists(olderIncompleteQuery, MongoOutboxRecordEntity::class.java)
        }
            .map { it.recordKey }
            .distinct()
            .take(batchSize)
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
 * DTO for aggregation pipeline result — maps _id field from $group stage.
 */
internal data class RecordKeyResult(
    val id: String = "",
    val minCreatedAt: Instant? = null,
)
