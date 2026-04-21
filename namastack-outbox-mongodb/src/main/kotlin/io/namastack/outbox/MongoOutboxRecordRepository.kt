package io.namastack.outbox

import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.aggregation.Aggregation
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import java.time.Instant

/**
 * MongoDB implementation of the OutboxRecordRepository and OutboxRecordStatusRepository interfaces.
 *
 * @author Stellar Hold
 * @since 1.5.0
 */
internal open class MongoOutboxRecordRepository(
    private val mongoTemplate: MongoTemplate,
    private val entityMapper: MongoOutboxRecordEntityMapper,
    private val clock: java.time.Clock,
) : OutboxRecordRepository,
    OutboxRecordStatusRepository {
    /**
     * Saves an outbox record. Inserts or updates the record document.
     *
     * Unlike the JDBC implementation which uses a try-update-then-insert pattern,
     * MongoDB's save operation is a native atomic upsert on the _id field.
     *
     * @param record the outbox record to save
     * @return the saved outbox record
     */
    override fun <T> save(record: OutboxRecord<T>): OutboxRecord<T> {
        val entity = entityMapper.map(record)
        mongoTemplate.save(entity)
        return record
    }

    /**
     * Finds all pending outbox records ordered by creation time.
     *
     * @return list of records with NEW status
     */
    override fun findPendingRecords(): List<OutboxRecord<*>> = findRecordsByStatus(OutboxRecordStatus.NEW)

    /**
     * Finds all completed outbox records ordered by creation time.
     *
     * @return list of records with COMPLETED status
     */
    override fun findCompletedRecords(): List<OutboxRecord<*>> = findRecordsByStatus(OutboxRecordStatus.COMPLETED)

    /**
     * Finds all failed outbox records ordered by creation time.
     *
     * @return list of records with FAILED status
     */
    override fun findFailedRecords(): List<OutboxRecord<*>> = findRecordsByStatus(OutboxRecordStatus.FAILED)

    /**
     * Finds all incomplete records for a specific record key ordered by creation time.
     *
     * @param recordKey the record key to filter by
     * @return list of incomplete records for the given key
     */
    override fun findIncompleteRecordsByRecordKey(recordKey: String): List<OutboxRecord<*>> {
        val query =
            Query(
                Criteria
                    .where("recordKey")
                    .`is`(recordKey)
                    .and("status")
                    .`is`(OutboxRecordStatus.NEW),
            ).with(Sort.by(Sort.Order.asc("createdAt")))

        return mongoTemplate
            .find(query, MongoOutboxRecordEntity::class.java)
            .map { entityMapper.map(it) }
    }

    /**
     * Finds record keys in specified partitions that are ready for processing.
     *
     * When [ignoreRecordKeysWithPreviousFailure] is true, uses a strict FIFO aggregation
     * pipeline that only returns a key if its absolute oldest incomplete record is ready.
     * This prevents out-of-order processing when earlier records are still pending or failed.
     *
     * When false, uses a standard aggregation that returns any key with at least one
     * ready record, regardless of older incomplete records.
     *
     * @param partitions the set of partition numbers to query
     * @param status the record status to match
     * @param batchSize the maximum number of record keys to return
     * @param ignoreRecordKeysWithPreviousFailure whether to enforce strict FIFO ordering
     * @return list of record keys ready for processing
     */
    override fun findRecordKeysInPartitions(
        partitions: Set<Int>,
        status: OutboxRecordStatus,
        batchSize: Int,
        ignoreRecordKeysWithPreviousFailure: Boolean,
    ): List<String> {
        val now = Instant.now(clock)

        val aggregation =
            if (ignoreRecordKeysWithPreviousFailure) {
                buildStrictFifoAggregation(partitions, status, now, batchSize)
            } else {
                buildStandardAggregation(partitions, status, now, batchSize)
            }

        return mongoTemplate
            .aggregate(
                aggregation,
                MongoOutboxRecordEntity.COLLECTION_NAME,
                RecordKeyDto::class.java,
            ).mappedResults
            .map { it.recordKey }
    }

    /**
     * Builds an aggregation pipeline that enforces strict FIFO processing per record key.
     *
     * Pipeline stages:
     * 1. Match all incomplete records (completedAt is null) in the given partitions
     * 2. Sort by recordKey and createdAt to prepare for grouping
     * 3. Group by recordKey, capturing the oldest record as the "blocker"
     * 4. Filter to only keys where the oldest record matches the target status and retry time
     * 5. Order by oldest record's creation time and limit results
     *
     * @param partitions the set of partition numbers to query
     * @param status the record status to match
     * @param now the current timestamp for retry comparison
     * @param batchSize the maximum number of record keys to return
     * @return the aggregation pipeline
     */
    private fun buildStrictFifoAggregation(
        partitions: Set<Int>,
        status: OutboxRecordStatus,
        now: Instant,
        batchSize: Int,
    ): Aggregation =
        Aggregation.newAggregation(
            // Match all incomplete records in partitions
            Aggregation.match(
                Criteria
                    .where("partitionNo")
                    .`in`(partitions)
                    .and("completedAt")
                    .`is`(null),
            ),
            // Sort to ensure the 'first' in group is the oldest
            Aggregation.sort(Sort.by(Sort.Direction.ASC, "recordKey", "createdAt")),
            // Group by recordKey and take the absolute oldest record (the "blocker")
            Aggregation
                .group("recordKey")
                .first(Aggregation.ROOT)
                .`as`("oldestDoc"),
            // Only process if the oldest record for this key is actually ready
            Aggregation.match(
                Criteria
                    .where("oldestDoc.status")
                    .`is`(status.name)
                    .and("oldestDoc.nextRetryAt")
                    .lte(now),
            ),
            // Order keys by their oldest record's creation time
            Aggregation.sort(Sort.by(Sort.Direction.ASC, "oldestDoc.createdAt")),
            Aggregation.limit(batchSize.toLong()),
            Aggregation
                .project()
                .andExclude("_id")
                .and("_id")
                .`as`("recordKey"),
        )

    /**
     * Builds a standard aggregation pipeline that finds record keys with ready records.
     *
     * Unlike [buildStrictFifoAggregation], this does not check for older incomplete records
     * blocking the key. It simply finds all records matching the criteria and groups by key.
     *
     * @param partitions the set of partition numbers to query
     * @param status the record status to match
     * @param now the current timestamp for retry comparison
     * @param batchSize the maximum number of record keys to return
     * @return the aggregation pipeline
     */
    private fun buildStandardAggregation(
        partitions: Set<Int>,
        status: OutboxRecordStatus,
        now: Instant,
        batchSize: Int,
    ): Aggregation =
        Aggregation.newAggregation(
            // Match all records that are ready for processing
            Aggregation.match(
                Criteria
                    .where("partitionNo")
                    .`in`(partitions)
                    .and("status")
                    .`is`(status.name)
                    .and("nextRetryAt")
                    .lte(now),
            ),
            // Group by recordKey to get unique keys and find oldest record for sorting
            Aggregation
                .group("recordKey")
                .min("createdAt")
                .`as`("minCreatedAt"),
            // Order by creation time of the oldest ready record
            Aggregation.sort(Sort.by(Sort.Direction.ASC, "minCreatedAt")),
            Aggregation.limit(batchSize.toLong()),
            Aggregation
                .project()
                .andExclude("_id")
                .and("_id")
                .`as`("recordKey"),
        )

    /**
     * Counts outbox records by status.
     *
     * @param status the record status to count
     * @return the number of records with the given status
     */
    override fun countByStatus(status: OutboxRecordStatus): Long {
        val query = Query(Criteria.where("status").`is`(status))
        return mongoTemplate.count(query, MongoOutboxRecordEntity::class.java)
    }

    /**
     * Counts outbox records by partition and status.
     *
     * @param partition the partition number to filter by
     * @param status the record status to filter by
     * @return the number of matching records
     */
    override fun countRecordsByPartition(
        partition: Int,
        status: OutboxRecordStatus,
    ): Long {
        val query =
            Query(
                Criteria
                    .where("partitionNo")
                    .`is`(partition)
                    .and("status")
                    .`is`(status),
            )
        return mongoTemplate.count(query, MongoOutboxRecordEntity::class.java)
    }

    /**
     * Deletes all outbox records with the specified status.
     *
     * @param status the record status to delete
     */
    override fun deleteByStatus(status: OutboxRecordStatus) {
        val query = Query(Criteria.where("status").`is`(status))
        mongoTemplate.remove(query, MongoOutboxRecordEntity::class.java)
    }

    /**
     * Deletes outbox records by record key and status.
     *
     * @param recordKey the record key to filter by
     * @param status the record status to filter by
     */
    override fun deleteByRecordKeyAndStatus(
        recordKey: String,
        status: OutboxRecordStatus,
    ) {
        val query =
            Query(
                Criteria
                    .where("recordKey")
                    .`is`(recordKey)
                    .and("status")
                    .`is`(status),
            )
        mongoTemplate.remove(query, MongoOutboxRecordEntity::class.java)
    }

    /**
     * Deletes a single outbox record by ID.
     *
     * @param id the record ID to delete
     */
    override fun deleteById(id: String) {
        val query = Query(Criteria.where("_id").`is`(id))
        mongoTemplate.remove(query, MongoOutboxRecordEntity::class.java)
    }

    /**
     * Finds all records with the specified status, ordered by creation time.
     *
     * @param status the record status to filter by
     * @return list of matching outbox records
     */
    private fun findRecordsByStatus(status: OutboxRecordStatus): List<OutboxRecord<*>> {
        val query =
            Query(Criteria.where("status").`is`(status))
                .with(Sort.by(Sort.Order.asc("createdAt")))
        return mongoTemplate
            .find(query, MongoOutboxRecordEntity::class.java)
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
