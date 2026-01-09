package io.namastack.outbox

import io.namastack.outbox.OutboxRecordStatus.COMPLETED
import io.namastack.outbox.OutboxRecordStatus.FAILED
import io.namastack.outbox.OutboxRecordStatus.NEW
import io.namastack.outbox.annotation.EnableOutbox
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

@JdbcTest
@ImportAutoConfiguration(JdbcOutboxAutoConfiguration::class, OutboxJacksonAutoConfiguration::class)
class JdbcOutboxRecordRepositoryTest {
    private val clock: Clock = Clock.systemDefaultZone()

    @Autowired
    private lateinit var jdbcOutboxRecordRepository: JdbcOutboxRecordRepository

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

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

        jdbcOutboxRecordRepository.save(record)

        val persistedRecord = jdbcOutboxRecordRepository.findIncompleteRecordsByRecordKey(recordKey).first()

        assertThat(persistedRecord.key).isEqualTo(record.key)
        assertThat(persistedRecord.payload).isEqualTo(record.payload)
        assertThat(persistedRecord.status).isEqualTo(record.status)
        assertThat(persistedRecord.failureCount).isEqualTo(record.failureCount)
        assertThat(persistedRecord.completedAt).isNull()
        assertThat(persistedRecord.createdAt).isCloseTo(record.createdAt, within(1, ChronoUnit.MILLIS))
        assertThat(persistedRecord.nextRetryAt).isCloseTo(record.nextRetryAt, within(1, ChronoUnit.MILLIS))
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

        jdbcOutboxRecordRepository.save(record)

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
                failureReason = record.failureReason,
                nextRetryAt = record.nextRetryAt,
                handlerId = record.handlerId,
                failureException = null,
            )

        jdbcOutboxRecordRepository.save(updatedRecord)

        val persistedUpdatedRecord = jdbcOutboxRecordRepository.findIncompleteRecordsByRecordKey(recordKey).first()

        assertThat(persistedUpdatedRecord.failureCount).isEqualTo(updatedRecord.failureCount)
    }

    @Test
    fun `finds pending records`() {
        createNewRecords(3)

        val records = jdbcOutboxRecordRepository.findPendingRecords()

        assertThat(records).hasSize(3)
        assertThat(records.map { it.status }).containsOnly(NEW)
    }

    @Test
    fun `finds completed records`() {
        createCompletedRecords(2)
        createNewRecords(1)

        val records = jdbcOutboxRecordRepository.findCompletedRecords()

        assertThat(records).hasSize(2)
        assertThat(records.map { it.status }).containsOnly(COMPLETED)
    }

    @Test
    fun `finds failed records`() {
        createFailedRecords(2)
        createNewRecords(1)

        val records = jdbcOutboxRecordRepository.findFailedRecords()

        assertThat(records).hasSize(2)
        assertThat(records.map { it.status }).containsOnly(FAILED)
    }

    @Test
    fun `finds incomplete records by record key`() {
        val recordKey = UUID.randomUUID().toString()
        createNewRecord(recordKey, 0)
        createNewRecord(recordKey, 1)
        createCompletedRecord(recordKey, 2)

        val records = jdbcOutboxRecordRepository.findIncompleteRecordsByRecordKey(recordKey)

        assertThat(records).hasSize(2)
        assertThat(records.map { it.status }).containsOnly(NEW)
    }

    @Test
    fun `counts records by status`() {
        createNewRecords(3)
        createCompletedRecords(2)
        createFailedRecords(1)

        val newCount = jdbcOutboxRecordRepository.countByStatus(NEW)
        val completedCount = jdbcOutboxRecordRepository.countByStatus(COMPLETED)
        val failedCount = jdbcOutboxRecordRepository.countByStatus(FAILED)

        assertThat(newCount).isEqualTo(3)
        assertThat(completedCount).isEqualTo(2)
        assertThat(failedCount).isEqualTo(1)
    }

    @Test
    fun `counts records by partition and status`() {
        createNewRecord("key1", 0)
        createNewRecord("key2", 0)
        createNewRecord("key3", 1)
        createCompletedRecord("key4", 0)

        val countPartition0New = jdbcOutboxRecordRepository.countRecordsByPartition(0, NEW)
        val countPartition1New = jdbcOutboxRecordRepository.countRecordsByPartition(1, NEW)
        val countPartition0Completed = jdbcOutboxRecordRepository.countRecordsByPartition(0, COMPLETED)

        assertThat(countPartition0New).isEqualTo(2)
        assertThat(countPartition1New).isEqualTo(1)
        assertThat(countPartition0Completed).isEqualTo(1)
    }

    @Test
    fun `deletes records by status`() {
        createNewRecords(3)
        createCompletedRecords(2)

        jdbcOutboxRecordRepository.deleteByStatus(COMPLETED)

        val remainingNew = jdbcOutboxRecordRepository.countByStatus(NEW)
        val remainingCompleted = jdbcOutboxRecordRepository.countByStatus(COMPLETED)

        assertThat(remainingNew).isEqualTo(3)
        assertThat(remainingCompleted).isEqualTo(0)
    }

    @Test
    fun `deletes records by record key and status`() {
        val recordKey = "test-key"
        createNewRecord(recordKey, 0)
        createNewRecord(recordKey, 1)
        createCompletedRecord(recordKey, 2)
        createNewRecord("other-key", 0)

        jdbcOutboxRecordRepository.deleteByRecordKeyAndStatus(recordKey, NEW)

        val remaining = jdbcOutboxRecordRepository.findIncompleteRecordsByRecordKey(recordKey)
        val otherRemaining = jdbcOutboxRecordRepository.findIncompleteRecordsByRecordKey("other-key")

        assertThat(remaining).isEmpty()
        assertThat(otherRemaining).hasSize(1)
    }

    @Test
    fun `deletes record by id`() {
        val record = createNewRecord("test-key", 0)

        jdbcOutboxRecordRepository.deleteById(record.id)

        val remaining = jdbcOutboxRecordRepository.findIncompleteRecordsByRecordKey("test-key")
        assertThat(remaining).isEmpty()
    }

    @Test
    fun `finds record keys in partitions without previous failure filter`() {
        val now = OffsetDateTime.now(clock)
        createRecordInPartition("key1", 0, now.minusSeconds(10))
        createRecordInPartition("key2", 1, now.minusSeconds(5))
        createRecordInPartition("key3", 2, now.minusSeconds(1))

        val recordKeys =
            jdbcOutboxRecordRepository.findRecordKeysInPartitions(
                partitions = setOf(0, 1),
                status = NEW,
                batchSize = 10,
                ignoreRecordKeysWithPreviousFailure = false,
            )

        assertThat(recordKeys).containsExactly("key1", "key2")
    }

    @Test
    fun `finds record keys in partitions with previous failure filter`() {
        val now = OffsetDateTime.now(clock)
        val key1 = "key1"
        val key2 = "key2"

        // key1: has previous incomplete (failed) record -> should be ignored
        createRecord(key1, 0, FAILED, now.minusSeconds(20))
        createRecordInPartition(key1, 0, now.minusSeconds(10))

        // key2: only has completed record (not a failure) -> should be included
        createCompletedRecordInPartition(key2, 0, now.minusSeconds(20))
        createRecordInPartition(key2, 0, now.minusSeconds(5))

        val recordKeys =
            jdbcOutboxRecordRepository.findRecordKeysInPartitions(
                partitions = setOf(0),
                status = NEW,
                batchSize = 10,
                ignoreRecordKeysWithPreviousFailure = true,
            )

        assertThat(recordKeys).containsExactly("key2")
    }

    @Test
    fun `respects batch size when finding record keys`() {
        val now = OffsetDateTime.now(clock)
        createRecordInPartition("key1", 0, now.minusSeconds(10))
        createRecordInPartition("key2", 0, now.minusSeconds(5))
        createRecordInPartition("key3", 0, now.minusSeconds(1))

        val recordKeys =
            jdbcOutboxRecordRepository.findRecordKeysInPartitions(
                partitions = setOf(0),
                status = NEW,
                batchSize = 2,
                ignoreRecordKeysWithPreviousFailure = false,
            )

        assertThat(recordKeys).hasSize(2)
        assertThat(recordKeys).containsExactly("key1", "key2")
    }

    @Test
    fun `returns record keys ordered by oldest created_at first`() {
        val now = OffsetDateTime.now(clock)
        // Create records in random order
        createRecordInPartition("key-middle", 0, now.minusSeconds(50))
        createRecordInPartition("key-newest", 0, now.minusSeconds(10))
        createRecordInPartition("key-oldest", 0, now.minusSeconds(100))

        val recordKeys =
            jdbcOutboxRecordRepository.findRecordKeysInPartitions(
                partitions = setOf(0),
                status = NEW,
                batchSize = 10,
                ignoreRecordKeysWithPreviousFailure = false,
            )

        // Should be ordered from oldest to newest
        assertThat(recordKeys).containsExactly("key-oldest", "key-middle", "key-newest")
    }

    @Test
    fun `returns record keys ordered by oldest created_at across multiple partitions`() {
        val now = OffsetDateTime.now(clock)
        // Create records in different partitions
        createRecordInPartition("key-partition1-old", 1, now.minusSeconds(80))
        createRecordInPartition("key-partition0-oldest", 0, now.minusSeconds(100))
        createRecordInPartition("key-partition1-new", 1, now.minusSeconds(30))
        createRecordInPartition("key-partition0-middle", 0, now.minusSeconds(60))

        val recordKeys =
            jdbcOutboxRecordRepository.findRecordKeysInPartitions(
                partitions = setOf(0, 1),
                status = NEW,
                batchSize = 10,
                ignoreRecordKeysWithPreviousFailure = false,
            )

        // Should be ordered from oldest to newest across all partitions
        assertThat(recordKeys).containsExactly(
            "key-partition0-oldest",
            "key-partition1-old",
            "key-partition0-middle",
            "key-partition1-new",
        )
    }

    private fun createNewRecords(count: Int) {
        repeat(count) {
            createNewRecord(UUID.randomUUID().toString(), 0)
        }
    }

    private fun createCompletedRecords(count: Int) {
        repeat(count) {
            createCompletedRecord(UUID.randomUUID().toString(), 0)
        }
    }

    private fun createFailedRecords(count: Int) {
        repeat(count) {
            createRecord(UUID.randomUUID().toString(), 0, FAILED, OffsetDateTime.now(clock))
        }
    }

    private fun createNewRecord(
        recordKey: String,
        partition: Int,
    ): OutboxRecord<String> = createRecord(recordKey, partition, NEW, OffsetDateTime.now(clock))

    private fun createCompletedRecord(
        recordKey: String,
        partition: Int,
    ): OutboxRecord<String> {
        val now = OffsetDateTime.now(clock)
        return createRecord(recordKey, partition, COMPLETED, now, completedAt = now)
    }

    private fun createRecordInPartition(
        recordKey: String,
        partition: Int,
        createdAt: OffsetDateTime,
    ): OutboxRecord<String> = createRecord(recordKey, partition, NEW, createdAt)

    private fun createCompletedRecordInPartition(
        recordKey: String,
        partition: Int,
        createdAt: OffsetDateTime,
    ): OutboxRecord<String> = createRecord(recordKey, partition, COMPLETED, createdAt, completedAt = createdAt)

    private fun createRecord(
        recordKey: String,
        partition: Int,
        status: OutboxRecordStatus,
        createdAt: OffsetDateTime,
        completedAt: OffsetDateTime? = null,
    ): OutboxRecord<String> {
        val record =
            OutboxRecord.restore(
                id = UUID.randomUUID().toString(),
                recordKey = recordKey,
                payload = "payload",
                context = emptyMap(),
                partition = partition,
                createdAt = createdAt,
                status = status,
                completedAt = completedAt,
                failureCount = 0,
                failureReason = null,
                nextRetryAt = createdAt,
                handlerId = "handlerId",
                failureException = null,
            )

        jdbcOutboxRecordRepository.save(record)
        return record
    }

    @EnableOutbox
    @SpringBootApplication
    class TestApplication
}
