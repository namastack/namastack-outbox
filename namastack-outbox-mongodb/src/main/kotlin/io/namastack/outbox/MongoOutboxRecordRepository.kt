package io.namastack.outbox

import org.bson.Document
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.aggregation.Aggregation
import org.springframework.data.mongodb.core.aggregation.AggregationOperation
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import java.time.Clock
import java.time.Instant

/**
 * MongoDB implementation of [OutboxRecordRepository].
 *
 * @author Stellar Hold
 * @since 1.5.0
 */
internal open class MongoOutboxRecordRepository(
    private val mongoTemplate: MongoTemplate,
    private val entityMapper: MongoOutboxRecordEntityMapper,
    private val clock: Clock,
    private val collectionNameResolver: MongoCollectionNameResolver,
) : OutboxRecordRepository {
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
        mongoTemplate.save(entity, collectionNameResolver.outboxRecords)
        return record
    }

    /**
     * Creates a new outbox record.
     *
     * @param record The outbox record to create
     * @return The created outbox record
     */
    override fun <T> create(record: OutboxRecord<T>): OutboxRecord<T> {
        val entity = entityMapper.map(record)
        mongoTemplate.insert(entity, collectionNameResolver.outboxRecords)
        return record
    }

    /**
     * Updates an existing outbox record. Does nothing if the record no longer exists.
     *
     * @param record The outbox record to update
     * @return True if the record was updated, false if it no longer exists
     */
    override fun <T> update(record: OutboxRecord<T>): Boolean {
        val entity = entityMapper.map(record)
        val query = Query(Criteria.where("_id").`is`(entity.id))
        val result = mongoTemplate.replace(query, entity, collectionNameResolver.outboxRecords)
        return result.matchedCount == 1L
    }

    /**
     * Updates only the processing state of an existing record. Does nothing if the record no longer exists.
     * The payload and context are neither serialized nor written.
     *
     * @param record The outbox record whose processing state changed
     * @return True if the record was updated, false if it no longer exists
     */
    override fun <T> updateProcessingState(record: OutboxRecord<T>): Boolean {
        val query = Query(Criteria.where("_id").`is`(record.id))
        val update =
            Update()
                .set("status", record.status)
                .set("completedAt", record.completedAt)
                .set("failureCount", record.failureCount)
                .set("failureReason", record.failureReason)
                .set("nextRetryAt", record.nextRetryAt)
        val result = mongoTemplate.updateFirst(query, update, collectionNameResolver.outboxRecords)
        return result.matchedCount == 1L
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
     * Finds at most [limit] completed records completed before the cutoff, oldest first.
     *
     * @param completedBefore exclusive upper bound for completedAt
     * @param limit maximum number of records to return
     * @return matching records, oldest first
     */
    override fun findCompletedRecords(
        completedBefore: Instant,
        limit: Int,
    ): List<OutboxRecord<*>> {
        require(limit > 0) { "limit must be positive but was $limit" }

        val query =
            Query(
                Criteria
                    .where("status")
                    .`is`(OutboxRecordStatus.COMPLETED)
                    .and("completedAt")
                    .lt(completedBefore),
            ).with(Sort.by(Sort.Order.asc("completedAt")))
                .limit(limit)

        return mongoTemplate
            .find(query, MongoOutboxRecordEntity::class.java, collectionNameResolver.outboxRecords)
            .map { entityMapper.map(it) }
    }

    /**
     * Finds all failed outbox records ordered by creation time.
     *
     * @return list of records with FAILED status
     */
    override fun findFailedRecords(): List<OutboxRecord<*>> = findRecordsByStatus(OutboxRecordStatus.FAILED)

    /**
     * Finds at most [limit] failed records whose last scheduled attempt is before the cutoff, oldest first.
     *
     * @param lastRetryBefore exclusive upper bound for nextRetryAt
     * @param limit maximum number of records to return
     * @return matching records, oldest first
     */
    override fun findFailedRecords(
        lastRetryBefore: Instant,
        limit: Int,
    ): List<OutboxRecord<*>> {
        require(limit > 0) { "limit must be positive but was $limit" }

        val query =
            Query(
                Criteria
                    .where("status")
                    .`is`(OutboxRecordStatus.FAILED)
                    .and("nextRetryAt")
                    .lt(lastRetryBefore),
            ).with(Sort.by(Sort.Order.asc("nextRetryAt")))
                .limit(limit)

        return mongoTemplate
            .find(query, MongoOutboxRecordEntity::class.java, collectionNameResolver.outboxRecords)
            .map { entityMapper.map(it) }
    }

    /**
     * Finds the `NEW` records of a specific record key ordered by creation time and id.
     *
     * @param recordKey the record key to filter by
     * @return list of `NEW` records for the given key
     */
    override fun findIncompleteRecordsByRecordKey(recordKey: String): List<OutboxRecord<*>> {
        val query =
            Query(
                Criteria
                    .where("recordKey")
                    .`is`(recordKey)
                    .and("status")
                    .`is`(OutboxRecordStatus.NEW),
            ).with(Sort.by(Sort.Direction.ASC, "createdAt", "_id"))

        return mongoTemplate
            .find(query, MongoOutboxRecordEntity::class.java, collectionNameResolver.outboxRecords)
            .map { entityMapper.map(it) }
    }

    /**
     * Loads NEW records for selected storage keys using bulk reads.
     *
     * The configured collection name is resolved before reading. Payload and context decoding
     * is deferred until a worker accesses a record. Future retries remain in each page, and one
     * loading predicate serves strict and non-strict processing. Missing keys receive empty pages.
     *
     * Records are loaded in one bulk read and grouped by their exact record key.
     *
     * @param recordKeys Selected storage keys, at most [OutboxRecordRepository.MAX_RECORD_KEYS_PER_FETCH] per call
     * @return An ordered, lazily decoded page for every requested key
     * @throws IllegalArgumentException if the portable key limit is exceeded
     */
    override fun findIncompleteRecordPages(recordKeys: Set<String>): Map<String, OutboxRecordPage> {
        require(recordKeys.size <= OutboxRecordRepository.MAX_RECORD_KEYS_PER_FETCH) {
            "A record fetch can contain at most ${OutboxRecordRepository.MAX_RECORD_KEYS_PER_FETCH} keys"
        }
        if (recordKeys.isEmpty()) return emptyMap()

        val query =
            Query(
                Criteria
                    .where("recordKey")
                    .`in`(recordKeys)
                    .and("status")
                    .`is`(OutboxRecordStatus.NEW)
                    .and("completedAt")
                    .`is`(null),
            ).with(Sort.by(Sort.Direction.ASC, "recordKey", "createdAt", "_id"))
        val rowsByKey =
            mongoTemplate
                .find(query, MongoOutboxRecordEntity::class.java, collectionNameResolver.outboxRecords)
                .groupBy { it.recordKey }

        return recordKeys.associateWith { key -> LazyOutboxRecordPage(rowsByKey[key].orEmpty(), entityMapper::map) }
    }

    /**
     * Finds record keys in specified partitions that are ready for processing.
     *
     * When [ignoreRecordKeysWithPreviousFailure] is true, uses a head-of-key aggregation pipeline
     * that only returns a key if its oldest incomplete record is ready. This prevents out-of-order
     * processing when earlier records are still pending or failed. Keys are ordered by the creation
     * time and id of that record.
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
                buildHeadOfKeyAggregation(partitions, status, now, batchSize)
            } else {
                buildStandardAggregation(partitions, status, now, batchSize)
            }

        return mongoTemplate
            .aggregate(
                aggregation,
                collectionNameResolver.outboxRecords,
                RecordKeyDto::class.java,
            ).mappedResults
            .map { it.recordKey }
    }

    /**
     * Counts outbox records by status.
     *
     * @param status the record status to count
     * @return the number of records with the given status
     */
    override fun countByStatus(status: OutboxRecordStatus): Long {
        val query = Query(Criteria.where("status").`is`(status))
        return mongoTemplate.count(query, collectionNameResolver.outboxRecords)
    }

    /**
     * Counts outbox records grouped by status in a single aggregation.
     *
     * Returns an entry for every requested status, with zero when no record has that status.
     * Returns an empty map without querying the data store when [statuses] is empty.
     *
     * @param statuses the statuses to count
     * @return the number of records per requested status
     */
    override fun countByStatuses(statuses: Set<OutboxRecordStatus>): Map<OutboxRecordStatus, Long> {
        if (statuses.isEmpty()) return emptyMap()

        val aggregation =
            Aggregation.newAggregation(
                Aggregation.match(Criteria.where("status").`in`(statuses.map { it.name })),
                Aggregation.group("status").count().`as`("count"),
            )
        val counts =
            mongoTemplate
                .aggregate(aggregation, collectionNameResolver.outboxRecords, Document::class.java)
                .mappedResults
                .associate { OutboxRecordStatus.valueOf(it.getString("_id")) to (it.get("count") as Number).toLong() }

        return statuses.associateWith { counts[it] ?: 0L }
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
        return mongoTemplate.count(query, collectionNameResolver.outboxRecords)
    }

    /**
     * Counts outbox records across the specified partitions by status in a single query.
     *
     * Includes records awaiting a future retry. Returns zero without querying the data store
     * when [partitions] is empty.
     *
     * @param partitions The partition numbers to count
     * @param status The status to count
     * @return Total number of matching records, or zero if the set is empty or no records match
     */
    override fun countRecordsByPartitions(
        partitions: Set<Int>,
        status: OutboxRecordStatus,
    ): Long {
        if (partitions.isEmpty()) return 0L

        val query =
            Query(
                Criteria
                    .where("partitionNo")
                    .`in`(partitions)
                    .and("status")
                    .`is`(status),
            )
        return mongoTemplate.count(query, collectionNameResolver.outboxRecords)
    }

    /**
     * Finds the earliest creation time of incomplete outbox records across the specified partitions by status.
     */
    override fun findOldestCreatedAtByPartitions(
        partitions: Set<Int>,
        status: OutboxRecordStatus,
    ): Instant? {
        if (partitions.isEmpty()) return null

        val match =
            Aggregation.match(
                Criteria
                    .where("partitionNo")
                    .`in`(partitions)
                    .and("status")
                    .`is`(status.name)
                    .and("completedAt")
                    .`is`(null),
            )
        return aggregateFirst(
            listOf(match, Aggregation.sort(Sort.by(Sort.Direction.ASC, "createdAt", "_id"))),
            "createdAt",
        )
    }

    /**
     * Finds the earliest next retry time of due outbox records across the specified partitions.
     *
     * With [ignoreRecordKeysWithPreviousFailure], only the oldest incomplete record of each key is
     * considered, as in [buildHeadOfKeyAggregation]. Due records are read in retry-time order and the
     * first one that heads its key is returned.
     */
    override fun findOldestDueNextRetryAtByPartitions(
        partitions: Set<Int>,
        status: OutboxRecordStatus,
        now: Instant,
        ignoreRecordKeysWithPreviousFailure: Boolean,
    ): Instant? {
        if (partitions.isEmpty()) return null

        return if (ignoreRecordKeysWithPreviousFailure) {
            aggregateFirst(headOfKeyStages(partitions, status, now, "nextRetryAt"), "nextRetryAt")
        } else {
            aggregateMin(listOf(readyRecordsMatchStage(partitions, status, now)), "nextRetryAt")
        }
    }

    /**
     * Deletes all outbox records with the specified status.
     *
     * @param status the record status to delete
     */
    override fun deleteByStatus(status: OutboxRecordStatus) {
        val query = Query(Criteria.where("status").`is`(status))
        mongoTemplate.remove(query, collectionNameResolver.outboxRecords)
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
        mongoTemplate.remove(query, collectionNameResolver.outboxRecords)
    }

    /**
     * Deletes a single outbox record by ID.
     *
     * @param id the record ID to delete
     */
    override fun deleteById(id: String) {
        val query = Query(Criteria.where("_id").`is`(id))
        mongoTemplate.remove(query, collectionNameResolver.outboxRecords)
    }

    /**
     * Deletes outbox records by ids.
     *
     * @param ids the record ids to delete
     * @return the number of records actually deleted
     */
    override fun deleteByIds(ids: Collection<String>): Int {
        if (ids.isEmpty()) return 0

        val query = Query(Criteria.where("_id").`in`(ids))
        return mongoTemplate.remove(query, collectionNameResolver.outboxRecords).deletedCount.toInt()
    }

    /**
     * Deletes at most [limit] completed records completed before the cutoff, oldest first.
     *
     * Not atomic: MongoDB has no limited delete, so the ids are selected first and deleted with [deleteByIds].
     *
     * @param completedBefore exclusive upper bound for completedAt
     * @param limit maximum number of records to delete
     * @return the number of records actually deleted
     */
    override fun deleteCompletedRecords(
        completedBefore: Instant,
        limit: Int,
    ): Int {
        require(limit > 0) { "limit must be positive but was $limit" }

        val query =
            Query(
                Criteria
                    .where("status")
                    .`is`(OutboxRecordStatus.COMPLETED)
                    .and("completedAt")
                    .lt(completedBefore),
            ).with(Sort.by(Sort.Order.asc("completedAt")))
                .limit(limit)
        query.fields().include("_id")

        val ids =
            mongoTemplate
                .find(query, Document::class.java, collectionNameResolver.outboxRecords)
                .map { it.get("_id").toString() }

        return deleteByIds(ids)
    }

    /**
     * Deletes at most [limit] failed records whose last scheduled attempt is before the cutoff, oldest first.
     *
     * Not atomic: MongoDB has no limited delete, so the ids are selected first and deleted with [deleteByIds].
     *
     * @param lastRetryBefore exclusive upper bound for nextRetryAt
     * @param limit maximum number of records to delete
     * @return the number of records actually deleted
     */
    override fun deleteFailedRecords(
        lastRetryBefore: Instant,
        limit: Int,
    ): Int {
        require(limit > 0) { "limit must be positive but was $limit" }

        val query =
            Query(
                Criteria
                    .where("status")
                    .`is`(OutboxRecordStatus.FAILED)
                    .and("nextRetryAt")
                    .lt(lastRetryBefore),
            ).with(Sort.by(Sort.Order.asc("nextRetryAt")))
                .limit(limit)
        query.fields().include("_id")

        val ids =
            mongoTemplate
                .find(query, Document::class.java, collectionNameResolver.outboxRecords)
                .map { it.get("_id").toString() }

        return deleteByIds(ids)
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
                .with(Sort.by(Sort.Direction.ASC, "createdAt", "_id"))
        return mongoTemplate
            .find(query, MongoOutboxRecordEntity::class.java, collectionNameResolver.outboxRecords)
            .map { entityMapper.map(it) }
    }

    /**
     * Builds the head-of-key aggregation pipeline for the record-key query.
     *
     * The due records are read in creation-time and id order; for each one, a lookup reads the oldest
     * incomplete record of its key. Only records that are that oldest record remain, so each key appears
     * at most once and the pipeline stops after [batchSize] keys.
     *
     * @param partitions the set of partition numbers to query
     * @param status the record status to match
     * @param now the current timestamp for retry comparison
     * @param batchSize the maximum number of record keys to return
     * @return the aggregation pipeline
     */
    private fun buildHeadOfKeyAggregation(
        partitions: Set<Int>,
        status: OutboxRecordStatus,
        now: Instant,
        batchSize: Int,
    ): Aggregation =
        Aggregation.newAggregation(
            headOfKeyStages(partitions, status, now, "createdAt") +
                listOf(
                    Aggregation.limit(batchSize.toLong()),
                    rawStage(Document("\$project", Document("_id", 0).append("recordKey", 1))),
                ),
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
            readyRecordsMatchStage(partitions, status, now),
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
     * Builds the head-of-key stages: due incomplete records with the given status in the partitions,
     * sorted by [sortField] and id, restricted to the records that are the oldest incomplete record of
     * their key (by creation time and id).
     *
     * The literal `completedAt: null` predicates allow the partial indexes to be used.
     */
    private fun headOfKeyStages(
        partitions: Set<Int>,
        status: OutboxRecordStatus,
        now: Instant,
        sortField: String,
    ): List<AggregationOperation> =
        listOf(
            Aggregation.match(
                Criteria
                    .where("partitionNo")
                    .`in`(partitions)
                    .and("completedAt")
                    .`is`(null)
                    .and("status")
                    .`is`(status.name)
                    .and("nextRetryAt")
                    .lte(now),
            ),
            Aggregation.sort(Sort.by(Sort.Direction.ASC, sortField, "_id")),
            rawStage(
                Document(
                    "\$lookup",
                    Document("from", collectionNameResolver.outboxRecords)
                        .append("let", Document("key", "\$recordKey"))
                        .append(
                            "pipeline",
                            listOf(
                                Document(
                                    "\$match",
                                    Document("completedAt", null)
                                        .append("\$expr", Document("\$eq", listOf("\$recordKey", "\$\$key"))),
                                ),
                                Document("\$sort", Document("createdAt", 1).append("_id", 1)),
                                Document("\$limit", 1),
                                Document("\$project", Document("_id", 1)),
                            ),
                        ).append("as", "head"),
                ),
            ),
            rawStage(
                Document(
                    "\$match",
                    Document(
                        "\$expr",
                        Document("\$eq", listOf("\$_id", Document("\$arrayElemAt", listOf("\$head._id", 0)))),
                    ),
                ),
            ),
        )

    /**
     * Runs the given stages and returns [field] of the first resulting document.
     *
     * @return the value of the first document, or null if no documents match
     */
    private fun aggregateFirst(
        stages: List<AggregationOperation>,
        field: String,
    ): Instant? =
        mongoTemplate
            .aggregate(
                Aggregation.newAggregation(
                    stages +
                        listOf(
                            Aggregation.limit(1),
                            rawStage(Document("\$project", Document("_id", 0).append("value", "\$$field"))),
                        ),
                ),
                collectionNameResolver.outboxRecords,
                MinInstantDto::class.java,
            ).mappedResults
            .firstOrNull()
            ?.value

    /**
     * Wraps a raw pipeline stage. Raw stages are passed to the server unchanged.
     */
    private fun rawStage(stage: Document): AggregationOperation = AggregationOperation { stage }

    /**
     * Runs the given stages followed by a group stage that reduces all documents to the minimum of [field].
     *
     * @return the minimum instant, or null if no documents match
     */
    private fun aggregateMin(
        stages: List<AggregationOperation>,
        field: String,
    ): Instant? =
        mongoTemplate
            .aggregate(
                Aggregation.newAggregation(stages + Aggregation.group().min(field).`as`("value")),
                collectionNameResolver.outboxRecords,
                MinInstantDto::class.java,
            ).uniqueMappedResult
            ?.value

    /**
     * Builds the stage that matches records in the given partitions and status that are due at [now].
     */
    private fun readyRecordsMatchStage(
        partitions: Set<Int>,
        status: OutboxRecordStatus,
        now: Instant,
    ): AggregationOperation =
        Aggregation.match(
            Criteria
                .where("partitionNo")
                .`in`(partitions)
                .and("status")
                .`is`(status.name)
                .and("nextRetryAt")
                .lte(now),
        )
}

/**
 * Result DTO for the aggregation pipeline.
 * Extracts the recordKey field from the project stage.
 */
internal data class RecordKeyDto(
    val recordKey: String = "",
)

/**
 * Result DTO for aggregation pipelines that reduce records to a single minimum instant.
 */
internal data class MinInstantDto(
    val value: Instant? = null,
)
