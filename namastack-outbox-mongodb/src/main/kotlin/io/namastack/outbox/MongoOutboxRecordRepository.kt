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

        val aggregation = if (ignoreRecordKeysWithPreviousFailure) {
            buildStrictFifoAggregation(partitions, status, now, batchSize)
        } else {
            buildStandardAggregation(partitions, status, now, batchSize)
        }

        return mongoTemplate.aggregate(
            aggregation,
            MongoOutboxRecordEntity.COLLECTION_NAME,
            RecordKeyDto::class.java
        ).mappedResults.map { it.recordKey }
    }

    private fun buildStrictFifoAggregation(
        partitions: Set<Int>,
        status: OutboxRecordStatus,
        now: Instant,
        batchSize: Int
    ): Aggregation {
        return Aggregation.newAggregation(
            // Match all incomplete records in partitions
            Aggregation.match(
                Criteria.where("partitionNo").`in`(partitions)
                    .and("completedAt").`is`(null)
            ),
            // Sort to ensure the 'first' in group is the oldest
            Aggregation.sort(Sort.by(Sort.Direction.ASC, "recordKey", "createdAt")),
            // Group by recordKey and take the absolute oldest record (the "blocker")
            Aggregation.group("recordKey")
                .first(Aggregation.ROOT).`as`("oldestDoc"),
            // Only process if the oldest record for this key is actually ready
            Aggregation.match(
                Criteria.where("oldestDoc.status").`is`(status.name)
                    .and("oldestDoc.nextRetryAt").lte(now)
            ),
            // Order keys by their oldest record's creation time
            Aggregation.sort(Sort.by(Sort.Direction.ASC, "oldestDoc.createdAt")),
            Aggregation.limit(batchSize.toLong()),
            Aggregation.project()
                .andExclude("_id")
                .and("_id").`as`("recordKey")
        )
    }

    private fun buildStandardAggregation(
        partitions: Set<Int>,
        status: OutboxRecordStatus,
        now: Instant,
        batchSize: Int
    ): Aggregation {
        return Aggregation.newAggregation(
            // Match all records that are ready for processing
            Aggregation.match(
                Criteria.where("partitionNo").`in`(partitions)
                    .and("status").`is`(status.name)
                    .and("nextRetryAt").lte(now)
            ),
            // Group by recordKey to get unique keys and find oldest record for sorting
            Aggregation.group("recordKey")
                .min("createdAt").`as`("minCreatedAt"),
            // Order by creation time of the oldest ready record
            Aggregation.sort(Sort.by(Sort.Direction.ASC, "minCreatedAt")),
            Aggregation.limit(batchSize.toLong()),
            Aggregation.project()
                .andExclude("_id")
                .and("_id").`as`("recordKey")
        )
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
        val query = Query(Criteria.where("_id").`is`(id))
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
