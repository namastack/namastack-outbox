package io.namastack.outbox

import com.mongodb.client.MongoClients
import io.namastack.outbox.OutboxRecordStatus.COMPLETED
import io.namastack.outbox.OutboxRecordStatus.FAILED
import io.namastack.outbox.OutboxRecordStatus.NEW
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.dropCollection
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit.MILLIS
import java.time.temporal.ChronoUnit.MINUTES
import java.util.UUID

@Testcontainers
class MongoOutboxRecordRepositoryTest {
    companion object {
        @JvmStatic
        val mongodb: MongoDBContainer =
            MongoDBContainer("mongo:8.0")
                .withReuse(true)
                .apply { start() }
    }

    private val clock: Clock = Clock.systemDefaultZone()
    private lateinit var mongoTemplate: MongoTemplate
    private lateinit var repository: MongoOutboxRecordRepository

    @BeforeEach
    fun setUp() {
        val client = MongoClients.create(mongodb.connectionString)
        mongoTemplate = MongoTemplate(client, "testdb")

        val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
        val serializer = JacksonOutboxPayloadSerializer(mapper)
        val entityMapper = MongoOutboxRecordEntityMapper(serializer)

        repository = MongoOutboxRecordRepository(mongoTemplate, entityMapper, clock)
        mongoTemplate.dropCollection<MongoOutboxRecordEntity>()
    }

    // ==================== Basic CRUD ====================

    @Test
    fun `saves an entity`() {
        val recordKey = UUID.randomUUID().toString()
        val record =
            OutboxRecord
                .Builder<String>()
                .key(recordKey)
                .payload("payload")
                .handlerId("handlerId")
                .build(clock)

        repository.save(record)

        val persistedRecord = repository.findIncompleteRecordsByRecordKey(recordKey).first()

        assertThat(persistedRecord.key).isEqualTo(record.key)
        assertThat(persistedRecord.payload).isEqualTo(record.payload)
        assertThat(persistedRecord.status).isEqualTo(record.status)
        assertThat(persistedRecord.failureCount).isEqualTo(record.failureCount)
        assertThat(persistedRecord.completedAt).isNull()
        assertThat(persistedRecord.createdAt).isCloseTo(record.createdAt, within(1, MILLIS))
        assertThat(persistedRecord.nextRetryAt).isCloseTo(record.nextRetryAt, within(1, MILLIS))
        assertThat(persistedRecord.handlerId).isEqualTo(record.handlerId)
    }

    @Test
    fun `updates an entity`() {
        val recordKey = UUID.randomUUID().toString()
        val record =
            OutboxRecord
                .Builder<String>()
                .key(recordKey)
                .payload("payload")
                .handlerId("handlerId")
                .build(clock)

        repository.save(record)

        val updatedRecord =
            OutboxRecord.restore(
                id = record.id,
                recordKey = record.key,
                payload = record.payload,
                context = record.context,
                partition = 1,
                createdAt = record.createdAt,
                status = record.status,
                completedAt = record.completedAt,
                failureCount = record.failureCount + 1,
                failureReason = "some failure",
                nextRetryAt = record.nextRetryAt,
                handlerId = record.handlerId,
                failureException = null,
            )

        repository.save(updatedRecord)

        val persistedUpdatedRecord = repository.findIncompleteRecordsByRecordKey(recordKey).first()

        assertThat(persistedUpdatedRecord.failureCount).isEqualTo(updatedRecord.failureCount)
        assertThat(persistedUpdatedRecord.failureReason).isEqualTo("some failure")
    }

    // ==================== Find by Status ====================

    @Test
    fun `finds pending records`() {
        createNewRecords(3)

        val records = repository.findPendingRecords()

        assertThat(records).hasSize(3)
        records.map { it.status }.forEach { status ->
            assertThat(status).isEqualTo(NEW)
        }
    }

    @Test
    fun `returns pending records ordered by createdAt asc`() {
        val now = Instant.now(clock).truncatedTo(MILLIS)
        val recordKey = UUID.randomUUID().toString()

        createNewRecordsForRecordKey(1, recordKey, NEW, now.minus(1, MINUTES))
        createNewRecordsForRecordKey(1, recordKey, NEW, now.minus(2, MINUTES))
        createNewRecordsForRecordKey(1, recordKey, NEW, now.minus(3, MINUTES))

        val records = repository.findPendingRecords()

        assertThat(records).hasSize(3)
        assertThat(records.map { it.createdAt })
            .containsExactly(
                now.minus(3, MINUTES),
                now.minus(2, MINUTES),
                now.minus(1, MINUTES),
            )
    }

    @Test
    fun `finds failed records`() {
        createFailedRecords(3)

        val records = repository.findFailedRecords()

        assertThat(records).hasSize(3)
        records.map { it.status }.forEach { status ->
            assertThat(status).isEqualTo(FAILED)
        }
    }

    @Test
    fun `finds completed records`() {
        createCompletedRecords(3)

        val records = repository.findCompletedRecords()

        assertThat(records).hasSize(3)
        records.map { it.status }.forEach { status ->
            assertThat(status).isEqualTo(COMPLETED)
        }
    }

    @Test
    fun `finds pending records returns empty when none exist`() {
        createCompletedRecords(2)
        createFailedRecords(1)

        val records = repository.findPendingRecords()

        assertThat(records).isEmpty()
    }

    @Test
    fun `finds failed records returns empty when none exist`() {
        createNewRecords(2)
        createCompletedRecords(1)

        val records = repository.findFailedRecords()

        assertThat(records).isEmpty()
    }

    @Test
    fun `finds completed records returns empty when none exist`() {
        createNewRecords(2)
        createFailedRecords(1)

        val records = repository.findCompletedRecords()

        assertThat(records).isEmpty()
    }

    // ==================== Count ====================

    @Test
    fun `counts records by status`() {
        createNewRecords(1)
        createCompletedRecords(2)
        createFailedRecords(3)

        assertThat(repository.countByStatus(NEW)).isEqualTo(1)
        assertThat(repository.countByStatus(COMPLETED)).isEqualTo(2)
        assertThat(repository.countByStatus(FAILED)).isEqualTo(3)
    }

    @Test
    fun `counts records by partition and status`() {
        createRecordWithPartition(UUID.randomUUID().toString(), NEW, 1)
        createRecordWithPartition(UUID.randomUUID().toString(), NEW, 1)
        createRecordWithPartition(UUID.randomUUID().toString(), FAILED, 1)
        createRecordWithPartition(UUID.randomUUID().toString(), NEW, 2)

        val newRecordsPartition1 = repository.countRecordsByPartition(1, NEW)
        val failedRecordsPartition1 = repository.countRecordsByPartition(1, FAILED)
        val newRecordsPartition2 = repository.countRecordsByPartition(2, NEW)

        assertThat(newRecordsPartition1).isEqualTo(2)
        assertThat(failedRecordsPartition1).isEqualTo(1)
        assertThat(newRecordsPartition2).isEqualTo(1)
    }

    // ==================== Delete ====================

    @Test
    fun `deletes records by status`() {
        createNewRecords()
        createFailedRecords()

        repository.deleteByStatus(NEW)
        assertThat(repository.countByStatus(NEW)).isEqualTo(0)
        assertThat(repository.countByStatus(FAILED)).isEqualTo(3)
    }

    @Test
    fun `deletes records by status does not affect other statuses`() {
        createNewRecords(2)
        createFailedRecords(3)
        createCompletedRecords(1)

        repository.deleteByStatus(FAILED)

        assertThat(repository.countByStatus(NEW)).isEqualTo(2)
        assertThat(repository.countByStatus(FAILED)).isEqualTo(0)
        assertThat(repository.countByStatus(COMPLETED)).isEqualTo(1)
    }

    @Test
    fun `deletes records by status and recordKey`() {
        val recordKey1 = UUID.randomUUID().toString()
        val recordKey2 = UUID.randomUUID().toString()
        createNewRecordsForRecordKey(1, recordKey1, NEW)
        createNewRecordsForRecordKey(1, recordKey1, FAILED)
        createNewRecordsForRecordKey(1, recordKey2, NEW)
        createNewRecordsForRecordKey(1, recordKey2, FAILED)

        repository.deleteByRecordKeyAndStatus(recordKey1, FAILED)

        assertThat(repository.findPendingRecords()).hasSize(2)
        assertThat(repository.findFailedRecords()).hasSize(1)
    }

    @Test
    fun `deletes records by record key and status only affects specified combination`() {
        val targetRecordKey = UUID.randomUUID().toString()
        val otherRecordKey = UUID.randomUUID().toString()

        createNewRecordsForRecordKey(2, targetRecordKey, NEW)
        createNewRecordsForRecordKey(1, targetRecordKey, FAILED)
        createNewRecordsForRecordKey(1, otherRecordKey, NEW)
        createNewRecordsForRecordKey(1, otherRecordKey, FAILED)

        repository.deleteByRecordKeyAndStatus(targetRecordKey, NEW)

        assertThat(repository.findIncompleteRecordsByRecordKey(targetRecordKey)).hasSize(0)
        assertThat(repository.findIncompleteRecordsByRecordKey(otherRecordKey)).hasSize(1)
        assertThat(repository.findFailedRecords()).hasSize(2)
        assertThat(repository.findCompletedRecords()).hasSize(0)
    }

    @Test
    fun `deletes record by id`() {
        val recordKey = UUID.randomUUID().toString()
        val record =
            OutboxRecord
                .Builder<String>()
                .key(recordKey)
                .payload("payload")
                .handlerId("handlerId")
                .build(clock)

        repository.save(record)
        assertThat(repository.findIncompleteRecordsByRecordKey(recordKey)).hasSize(1)

        repository.deleteById(record.id)
        assertThat(repository.findIncompleteRecordsByRecordKey(recordKey)).isEmpty()
    }

    // ==================== Find Incomplete by RecordKey ====================

    @Test
    fun `finds all incomplete records by record key ordered by created date`() {
        val recordKey = UUID.randomUUID().toString()
        val now = Instant.now(clock).truncatedTo(MILLIS)

        createNewRecordsForRecordKey(1, recordKey, NEW, now.minus(3, MINUTES))
        createNewRecordsForRecordKey(1, recordKey, NEW, now.minus(1, MINUTES))
        createNewRecordsForRecordKey(1, recordKey, NEW, now.minus(2, MINUTES))
        createNewRecordsForRecordKey(1, "other-record-key", NEW, now)

        val records = repository.findIncompleteRecordsByRecordKey(recordKey)

        assertThat(records).hasSize(3)
        assertThat(records.map { it.createdAt }).containsExactly(
            now.minus(3, MINUTES),
            now.minus(2, MINUTES),
            now.minus(1, MINUTES),
        )
        assertThat(records.map { it.key }).allMatch { it == recordKey }
    }

    @Test
    fun `finds incomplete records by record key returns empty when none exist`() {
        val recordKey = UUID.randomUUID().toString()
        createCompletedRecordsForRecordKey(2, recordKey)

        val records = repository.findIncompleteRecordsByRecordKey(recordKey)

        assertThat(records).isEmpty()
    }

    // ==================== Partition-based queries ====================

    @Test
    fun `finds record keys in partitions`() {
        val partition1RecordKey = UUID.randomUUID().toString()
        val partition2RecordKey = UUID.randomUUID().toString()

        createRecordWithPartition(partition1RecordKey, NEW, 1)
        createRecordWithPartition(partition2RecordKey, NEW, 2)

        val partition1RecordKeys = repository.findRecordKeysInPartitions(setOf(1), NEW, 10, true)
        val partition2RecordKeys = repository.findRecordKeysInPartitions(setOf(2), NEW, 10, true)

        assertThat(partition1RecordKeys).containsExactly(partition1RecordKey)
        assertThat(partition2RecordKeys).containsExactly(partition2RecordKey)
    }

    @Test
    fun `finds record keys in multiple partitions`() {
        val recordKey1 = UUID.randomUUID().toString()
        val recordKey2 = UUID.randomUUID().toString()
        val recordKey3 = UUID.randomUUID().toString()

        createRecordWithPartition(recordKey1, NEW, 1)
        createRecordWithPartition(recordKey2, NEW, 2)
        createRecordWithPartition(recordKey3, NEW, 3)

        val recordKeys = repository.findRecordKeysInPartitions(setOf(1, 2), NEW, 10, true)

        assertThat(recordKeys).containsExactlyInAnyOrder(recordKey1, recordKey2)
        assertThat(recordKeys).doesNotContain(recordKey3)
    }

    @Test
    fun `finds record keys in partitions with strict ordering`() {
        val recordKey = UUID.randomUUID().toString()
        val now = Instant.now(clock).truncatedTo(MILLIS)

        createRecordWithPartitionAndTime(recordKey, NEW, 1, now.minus(5, MINUTES))
        createRecordWithPartitionAndTime(recordKey, NEW, 1, now.minus(2, MINUTES))

        val result = repository.findRecordKeysInPartitions(setOf(1), NEW, 10, true)

        assertThat(result).containsExactly(recordKey)
    }

    @Test
    fun `findRecordKeysInPartitions processes oldest record when multiple NEW records exist`() {
        val recordKey = UUID.randomUUID().toString()
        val now = Instant.now(clock)

        createRecordWithPartitionAndTime(recordKey, NEW, 1, now.minus(3, MINUTES))
        createRecordWithPartitionAndTime(recordKey, NEW, 1, now.minus(1, MINUTES))

        val result = repository.findRecordKeysInPartitions(setOf(1), NEW, 10, true)

        assertThat(result).contains(recordKey)
    }

    @Test
    fun `findRecordKeysInPartitions allows processing when oldest record is ready`() {
        val recordKey = UUID.randomUUID().toString()
        val now = Instant.now(clock)

        createRecordWithPartitionAndTime(recordKey, NEW, 1, now.minus(3, MINUTES))

        val result = repository.findRecordKeysInPartitions(setOf(1), NEW, 10, true)

        assertThat(result).contains(recordKey)
    }

    @Test
    fun `findRecordKeysInPartitions orders by oldest record creation time across partitions`() {
        val now = Instant.now(clock)

        val recordKey1 = UUID.randomUUID().toString()
        val recordKey2 = UUID.randomUUID().toString()
        val recordKey3 = UUID.randomUUID().toString()

        createRecordWithPartitionAndTime(recordKey2, NEW, 1, now.minus(2, MINUTES))
        createRecordWithPartitionAndTime(recordKey1, NEW, 2, now.minus(3, MINUTES))
        createRecordWithPartitionAndTime(recordKey3, NEW, 1, now.minus(1, MINUTES))

        val result = repository.findRecordKeysInPartitions(setOf(1, 2), NEW, 10, true)

        assertThat(result).containsExactly(recordKey1, recordKey2, recordKey3)
    }

    @Test
    fun `findRecordKeysInPartitions filters out keys with older incomplete records`() {
        val keyWithBlockedOrder = "blocked-key"
        val keyReadyToProcess = "ready-key"
        val now = Instant.now(clock).truncatedTo(MILLIS)

        // blocked-key: r1 is older and incomplete (nextRetryAt in future), r2 is ready but blocked by r1
        createRecord(keyWithBlockedOrder, NEW, 1, now.minus(10, MINUTES), nextRetryAt = now.plus(1, MINUTES))
        createRecord(keyWithBlockedOrder, NEW, 1, now.minus(5, MINUTES), nextRetryAt = now.minus(1, MINUTES))

        // ready-key: r3 is the oldest for this key and is ready
        createRecord(keyReadyToProcess, NEW, 1, now.minus(8, MINUTES), nextRetryAt = now.minus(1, MINUTES))

        val result = repository.findRecordKeysInPartitions(setOf(1), NEW, 10, true)

        assertThat(result).containsExactly(keyReadyToProcess)
    }

    // ==================== Helper methods ====================

    private fun createFailedRecords(count: Int = 3) {
        val now = Instant.now(clock)
        (0 until count).forEach { _ ->
            repository.save(
                OutboxRecord.restore(
                    id = UUID.randomUUID().toString(),
                    recordKey = UUID.randomUUID().toString(),
                    payload = "payload",
                    context = mapOf("key1" to "value1", "key2" to "value2"),
                    partition = 1,
                    createdAt = now,
                    status = FAILED,
                    completedAt = null,
                    failureCount = 3,
                    failureReason = "Processing failed",
                    nextRetryAt = now,
                    handlerId = "handlerId",
                    failureException = null,
                ),
            )
        }
    }

    private fun createCompletedRecords(count: Int = 3) {
        val now = Instant.now(clock)
        (0 until count).forEach { _ ->
            repository.save(
                OutboxRecord.restore(
                    id = UUID.randomUUID().toString(),
                    recordKey = UUID.randomUUID().toString(),
                    payload = "payload",
                    context = mapOf("key1" to "value1", "key2" to "value2"),
                    partition = 1,
                    createdAt = now,
                    status = COMPLETED,
                    completedAt = now,
                    failureCount = 0,
                    failureReason = null,
                    nextRetryAt = now,
                    handlerId = "handlerId",
                    failureException = null,
                ),
            )
        }
    }

    private fun createNewRecords(count: Int = 3) {
        val now = Instant.now(clock)
        (0 until count).forEach { _ ->
            repository.save(
                OutboxRecord.restore(
                    id = UUID.randomUUID().toString(),
                    recordKey = UUID.randomUUID().toString(),
                    payload = "payload",
                    context = mapOf("key1" to "value1", "key2" to "value2"),
                    partition = 1,
                    createdAt = now,
                    status = NEW,
                    completedAt = null,
                    failureCount = 0,
                    failureReason = null,
                    nextRetryAt = now,
                    handlerId = "handlerId",
                    failureException = null,
                ),
            )
        }
    }

    private fun createNewRecordsForRecordKey(
        count: Int = 3,
        recordKey: String,
        status: OutboxRecordStatus = NEW,
        createdAt: Instant = Instant.now(clock),
    ) {
        (0 until count).forEach { _ ->
            repository.save(
                OutboxRecord.restore(
                    id = UUID.randomUUID().toString(),
                    recordKey = recordKey,
                    payload = "payload",
                    context = mapOf("key1" to "value1", "key2" to "value2"),
                    partition = 1,
                    createdAt = createdAt,
                    status = status,
                    completedAt = null,
                    failureCount = 0,
                    failureReason = null,
                    nextRetryAt = createdAt,
                    handlerId = "handlerId",
                    failureException = null,
                ),
            )
        }
    }

    private fun createRecordWithPartition(
        recordKey: String,
        status: OutboxRecordStatus,
        partition: Int,
        createdAt: Instant = Instant.now(clock),
    ) {
        repository.save(
            OutboxRecord.restore(
                id = UUID.randomUUID().toString(),
                recordKey = recordKey,
                payload = "test-payload",
                context = mapOf("key1" to "value1", "key2" to "value2"),
                partition = partition,
                createdAt = createdAt,
                status = status,
                completedAt = if (status == COMPLETED) createdAt else null,
                failureCount = 0,
                failureReason = null,
                nextRetryAt = createdAt,
                handlerId = "handlerId",
                failureException = null,
            ),
        )
    }

    private fun createCompletedRecordsForRecordKey(
        count: Int,
        recordKey: String,
        createdAt: Instant = Instant.now(clock),
    ) {
        (0 until count).forEach { _ ->
            repository.save(
                OutboxRecord.restore(
                    id = UUID.randomUUID().toString(),
                    recordKey = recordKey,
                    payload = "test-payload",
                    context = mapOf("key1" to "value1", "key2" to "value2"),
                    partition = 1,
                    createdAt = createdAt,
                    status = COMPLETED,
                    completedAt = createdAt,
                    failureCount = 0,
                    failureReason = null,
                    nextRetryAt = createdAt,
                    handlerId = "handlerId",
                    failureException = null,
                ),
            )
        }
    }

    private fun createRecordWithPartitionAndTime(
        recordKey: String,
        status: OutboxRecordStatus,
        partition: Int,
        createdAt: Instant,
    ): OutboxRecord<String> {
        val record =
            OutboxRecord.restore(
                id = UUID.randomUUID().toString(),
                recordKey = recordKey,
                payload = "payload",
                context = mapOf("key1" to "value1", "key2" to "value2"),
                partition = partition,
                createdAt = createdAt,
                status = status,
                completedAt = null,
                failureCount = 0,
                failureReason = null,
                nextRetryAt = createdAt,
                handlerId = "handlerId",
                failureException = null,
            )

        repository.save(record)
        return record
    }

    private fun createRecord(
        recordKey: String,
        status: OutboxRecordStatus,
        partition: Int,
        createdAt: Instant,
        nextRetryAt: Instant = createdAt,
    ) {
        val record =
            OutboxRecord.restore(
                id = UUID.randomUUID().toString(),
                recordKey = recordKey,
                payload = "payload",
                context = emptyMap(),
                partition = partition,
                createdAt = createdAt,
                status = status,
                completedAt = if (status == COMPLETED) createdAt else null,
                failureCount = 0,
                failureReason = null,
                nextRetryAt = nextRetryAt,
                handlerId = "handler",
                failureException = null,
            )
        repository.save(record)
    }
}
