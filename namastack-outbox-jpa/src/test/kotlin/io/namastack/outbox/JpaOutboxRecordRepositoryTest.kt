package io.namastack.outbox

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.OutboxRecordStatus.COMPLETED
import io.namastack.outbox.OutboxRecordStatus.FAILED
import io.namastack.outbox.OutboxRecordStatus.NEW
import io.namastack.outbox.annotation.EnableOutbox
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

@DataJpaTest
@ImportAutoConfiguration(JpaOutboxAutoConfiguration::class, OutboxJacksonAutoConfiguration::class)
class JpaOutboxRecordRepositoryTest {
    private val clock: Clock = Clock.systemDefaultZone()

    @Autowired
    private lateinit var jpaOutboxRecordRepository: JpaOutboxRecordRepository

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

        jpaOutboxRecordRepository.save(record)

        val persistedRecord = jpaOutboxRecordRepository.findIncompleteRecordsByRecordKey(recordKey).first()

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

        jpaOutboxRecordRepository.save(record)

        val updatedRecord =
            OutboxRecord.restore(
                id = record.id,
                recordKey = record.key,
                payload = record.payload,
                attributes = record.attributes,
                partition = 1,
                createdAt = record.createdAt,
                status = record.status,
                completedAt = record.completedAt,
                failureCount = record.failureCount + 1,
                nextRetryAt = record.nextRetryAt,
                handlerId = record.handlerId,
            )

        jpaOutboxRecordRepository.save(updatedRecord)

        val persistedUpdatedRecord = jpaOutboxRecordRepository.findIncompleteRecordsByRecordKey(recordKey).first()

        assertThat(persistedUpdatedRecord.failureCount).isEqualTo(updatedRecord.failureCount)
    }

    @Test
    fun `finds pending records`() {
        createNewRecords(3)

        val records = jpaOutboxRecordRepository.findPendingRecords()

        assertThat(records).hasSize(3)
        records.map { it.status }.forEach { status ->
            assertThat(status).isEqualTo(NEW)
        }
    }

    @Test
    fun `returns pending records ordered by createdAt asc`() {
        val now = OffsetDateTime.now(clock)
        val recordKey = UUID.randomUUID().toString()

        createNewRecordsForRecordKey(1, recordKey, NEW, now.minusMinutes(1))
        createNewRecordsForRecordKey(1, recordKey, NEW, now.minusMinutes(2))
        createNewRecordsForRecordKey(1, recordKey, NEW, now.minusMinutes(3))

        val records = jpaOutboxRecordRepository.findPendingRecords()

        assertThat(records).hasSize(3)
        assertThat(records.map { it.createdAt })
            .containsExactly(
                now.minusMinutes(3),
                now.minusMinutes(2),
                now.minusMinutes(1),
            )
    }

    @Test
    fun `finds failed records`() {
        createFailedRecords(3)

        val records = jpaOutboxRecordRepository.findFailedRecords()

        assertThat(records).hasSize(3)
        records.map { it.status }.forEach { status ->
            assertThat(status).isEqualTo(FAILED)
        }
    }

    @Test
    fun `finds completed records`() {
        createCompletedRecords(3)

        val records = jpaOutboxRecordRepository.findCompletedRecords()

        assertThat(records).hasSize(3)
        records.map { it.status }.forEach { status ->
            assertThat(status).isEqualTo(COMPLETED)
        }
    }

    @Test
    fun `counts records by status`() {
        createNewRecords(1)
        createCompletedRecords(2)
        createFailedRecords(3)

        assertThat(jpaOutboxRecordRepository.countByStatus(NEW)).isEqualTo(1)
        assertThat(jpaOutboxRecordRepository.countByStatus(COMPLETED)).isEqualTo(2)
        assertThat(jpaOutboxRecordRepository.countByStatus(FAILED)).isEqualTo(3)
    }

    @Test
    fun `deletes records by status`() {
        createNewRecords()
        createFailedRecords()

        jpaOutboxRecordRepository.deleteByStatus(NEW)
        assertThat(jpaOutboxRecordRepository.countByStatus(NEW)).isEqualTo(0)
        assertThat(jpaOutboxRecordRepository.countByStatus(FAILED)).isEqualTo(3)
    }

    @Test
    fun `deletes records by status and recordKey`() {
        val recordKey1 = UUID.randomUUID().toString()
        val recordKey2 = UUID.randomUUID().toString()
        createNewRecordsForRecordKey(1, recordKey1, NEW)
        createNewRecordsForRecordKey(1, recordKey1, FAILED)
        createNewRecordsForRecordKey(1, recordKey2, NEW)
        createNewRecordsForRecordKey(1, recordKey2, FAILED)

        jpaOutboxRecordRepository.deleteByRecordKeyAndStatus(recordKey1, FAILED)

        assertThat(jpaOutboxRecordRepository.findPendingRecords()).hasSize(2)
        assertThat(jpaOutboxRecordRepository.findFailedRecords()).hasSize(1)
    }

    @Test
    fun `finds all incomplete records by record key ordered by created date`() {
        val recordKey = UUID.randomUUID().toString()
        val now = OffsetDateTime.now(clock)

        createNewRecordsForRecordKey(1, recordKey, NEW, now.minusMinutes(3))
        createNewRecordsForRecordKey(1, recordKey, NEW, now.minusMinutes(1))
        createNewRecordsForRecordKey(1, recordKey, NEW, now.minusMinutes(2))
        createNewRecordsForRecordKey(1, "other-record-key", NEW, now)

        val records = jpaOutboxRecordRepository.findIncompleteRecordsByRecordKey(recordKey)

        assertThat(records).hasSize(3)
        assertThat(records.map { it.createdAt }).containsExactly(
            now.minusMinutes(3),
            now.minusMinutes(2),
            now.minusMinutes(1),
        )
        assertThat(records.map { it.key }).allMatch { it == recordKey }
    }

    @Test
    fun `finds record keys in partitions`() {
        val partition1RecordKey = UUID.randomUUID().toString()
        val partition2RecordKey = UUID.randomUUID().toString()

        createRecordWithPartition(partition1RecordKey, NEW, 1)
        createRecordWithPartition(partition2RecordKey, NEW, 2)

        val partition1RecordKeys =
            jpaOutboxRecordRepository.findRecordKeysInPartitions(
                setOf(1),
                NEW,
                10,
                true,
            )

        val partition2RecordKeys =
            jpaOutboxRecordRepository.findRecordKeysInPartitions(
                setOf(2),
                NEW,
                10,
                true,
            )

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

        val recordKeys =
            jpaOutboxRecordRepository.findRecordKeysInPartitions(
                setOf(1, 2),
                NEW,
                10,
                true,
            )

        assertThat(recordKeys).containsExactlyInAnyOrder(recordKey1, recordKey2)
        assertThat(recordKeys).doesNotContain(recordKey3)
    }

    @Test
    fun `counts records by partition and status`() {
        createRecordWithPartition(UUID.randomUUID().toString(), NEW, 1)
        createRecordWithPartition(UUID.randomUUID().toString(), NEW, 1)
        createRecordWithPartition(UUID.randomUUID().toString(), FAILED, 1)
        createRecordWithPartition(UUID.randomUUID().toString(), NEW, 2)

        val newRecordsPartition1 = jpaOutboxRecordRepository.countRecordsByPartition(1, NEW)
        val failedRecordsPartition1 = jpaOutboxRecordRepository.countRecordsByPartition(1, FAILED)
        val newRecordsPartition2 = jpaOutboxRecordRepository.countRecordsByPartition(2, NEW)

        assertThat(newRecordsPartition1).isEqualTo(2)
        assertThat(failedRecordsPartition1).isEqualTo(1)
        assertThat(newRecordsPartition2).isEqualTo(1)
    }

    @Test
    fun `finds pending records returns empty when none exist`() {
        createCompletedRecords(2)
        createFailedRecords(1)

        val records = jpaOutboxRecordRepository.findPendingRecords()

        assertThat(records).isEmpty()
    }

    @Test
    fun `finds failed records returns empty when none exist`() {
        createNewRecords(2)
        createCompletedRecords(1)

        val records = jpaOutboxRecordRepository.findFailedRecords()

        assertThat(records).isEmpty()
    }

    @Test
    fun `finds completed records returns empty when none exist`() {
        createNewRecords(2)
        createFailedRecords(1)

        val records = jpaOutboxRecordRepository.findCompletedRecords()

        assertThat(records).isEmpty()
    }

    @Test
    fun `finds incomplete records by record key returns empty when none exist`() {
        val recordKey = UUID.randomUUID().toString()
        createCompletedRecordsForRecordKey(2, recordKey)

        val records = jpaOutboxRecordRepository.findIncompleteRecordsByRecordKey(recordKey)

        assertThat(records).isEmpty()
    }

    @Test
    fun `deletes records by status does not affect other statuses`() {
        createNewRecords(2)
        createFailedRecords(3)
        createCompletedRecords(1)

        jpaOutboxRecordRepository.deleteByStatus(FAILED)

        assertThat(jpaOutboxRecordRepository.countByStatus(NEW)).isEqualTo(2)
        assertThat(jpaOutboxRecordRepository.countByStatus(FAILED)).isEqualTo(0)
        assertThat(jpaOutboxRecordRepository.countByStatus(COMPLETED)).isEqualTo(1)
    }

    @Test
    fun `deletes records by record key and status only affects specified combination`() {
        val targetRecordKey = UUID.randomUUID().toString()
        val otherRecordKey = UUID.randomUUID().toString()

        createNewRecordsForRecordKey(2, targetRecordKey, NEW)
        createNewRecordsForRecordKey(1, targetRecordKey, FAILED)
        createNewRecordsForRecordKey(1, otherRecordKey, NEW)
        createNewRecordsForRecordKey(1, otherRecordKey, FAILED)

        jpaOutboxRecordRepository.deleteByRecordKeyAndStatus(targetRecordKey, NEW)

        assertThat(jpaOutboxRecordRepository.findIncompleteRecordsByRecordKey(targetRecordKey)).hasSize(0)
        assertThat(jpaOutboxRecordRepository.findIncompleteRecordsByRecordKey(otherRecordKey)).hasSize(1)
        assertThat(jpaOutboxRecordRepository.findFailedRecords()).hasSize(2)
        assertThat(jpaOutboxRecordRepository.findCompletedRecords()).hasSize(0)
    }

    @Test
    fun `findRecordKeysInPartitions processes oldest record when multiple NEW records exist`() {
        val recordKey = UUID.randomUUID().toString()
        val now = OffsetDateTime.now(clock)

        createRecordWithPartitionAndTime(recordKey, NEW, 1, now.minusMinutes(3))
        createRecordWithPartitionAndTime(recordKey, NEW, 1, now.minusMinutes(1))

        val result = jpaOutboxRecordRepository.findRecordKeysInPartitions(setOf(1), NEW, 10, true)

        assertThat(result).contains(recordKey)
    }

    @Test
    fun `findRecordKeysInPartitions allows processing when oldest record is ready`() {
        val recordKey = UUID.randomUUID().toString()
        val now = OffsetDateTime.now(clock)

        createRecordWithPartitionAndTime(recordKey, NEW, 1, now.minusMinutes(3))

        val result = jpaOutboxRecordRepository.findRecordKeysInPartitions(setOf(1), NEW, 10, true)

        assertThat(result).contains(recordKey)
    }

    @Test
    fun `findRecordKeysInPartitions orders by oldest record creation time across partitions`() {
        val now = OffsetDateTime.now(clock)

        val recordKey1 = UUID.randomUUID().toString()
        val recordKey2 = UUID.randomUUID().toString()
        val recordKey3 = UUID.randomUUID().toString()

        createRecordWithPartitionAndTime(recordKey2, NEW, 1, now.minusMinutes(2))
        createRecordWithPartitionAndTime(recordKey1, NEW, 2, now.minusMinutes(3))
        createRecordWithPartitionAndTime(recordKey3, NEW, 1, now.minusMinutes(1))

        val result = jpaOutboxRecordRepository.findRecordKeysInPartitions(setOf(1, 2), NEW, 10, true)

        assertThat(result).containsExactly(recordKey1, recordKey2, recordKey3)
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

        jpaOutboxRecordRepository.save(record)
        assertThat(jpaOutboxRecordRepository.findIncompleteRecordsByRecordKey(recordKey)).hasSize(1)

        jpaOutboxRecordRepository.deleteById(record.id)
        assertThat(jpaOutboxRecordRepository.findIncompleteRecordsByRecordKey(recordKey)).isEmpty()
    }

    @Test
    fun `does not delete record by id when not existing`() {
        val entityManager = mockk<EntityManager>(relaxed = true)
        val entityMapper = mockk<OutboxRecordEntityMapper>(relaxed = true)
        every { entityManager.find(OutboxRecordEntity::class.java, any()) } returns null
        val recordRepository = JpaOutboxRecordRepository(entityManager, transactionTemplate, entityMapper, clock)

        recordRepository.deleteById(UUID.randomUUID().toString())

        verify(exactly = 1) { entityManager.find(OutboxRecordEntity::class.java, any()) }
        verify(exactly = 0) { entityManager.remove(any<OutboxRecordEntity>()) }
    }

    private fun createFailedRecords(count: Int = 3) {
        val now = OffsetDateTime.now(clock)
        (0 until count).forEach { _ ->
            jpaOutboxRecordRepository.save(
                OutboxRecord.restore(
                    id = UUID.randomUUID().toString(),
                    recordKey = UUID.randomUUID().toString(),
                    payload = "payload",
                    attributes = emptyMap(),
                    partition = 1,
                    createdAt = now,
                    status = FAILED,
                    completedAt = null,
                    failureCount = 3,
                    nextRetryAt = now,
                    handlerId = "handlerId",
                ),
            )
        }
    }

    private fun createCompletedRecords(count: Int = 3) {
        val now = OffsetDateTime.now(clock)
        (0 until count).forEach { _ ->
            jpaOutboxRecordRepository.save(
                OutboxRecord.restore(
                    id = UUID.randomUUID().toString(),
                    recordKey = UUID.randomUUID().toString(),
                    payload = "payload",
                    attributes = emptyMap(),
                    partition = 1,
                    createdAt = now,
                    status = COMPLETED,
                    completedAt = now,
                    failureCount = 0,
                    nextRetryAt = now,
                    handlerId = "handlerId",
                ),
            )
        }
    }

    private fun createNewRecords(count: Int = 3) {
        val now = OffsetDateTime.now(clock)
        (0 until count).forEach { _ ->
            jpaOutboxRecordRepository.save(
                OutboxRecord.restore(
                    id = UUID.randomUUID().toString(),
                    recordKey = UUID.randomUUID().toString(),
                    payload = "payload",
                    attributes = emptyMap(),
                    partition = 1,
                    createdAt = now,
                    status = NEW,
                    completedAt = null,
                    failureCount = 0,
                    nextRetryAt = now,
                    handlerId = "handlerId",
                ),
            )
        }
    }

    private fun createNewRecordsForRecordKey(
        count: Int = 3,
        recordKey: String,
        status: OutboxRecordStatus = NEW,
        createdAt: OffsetDateTime = OffsetDateTime.now(clock),
    ) {
        (0 until count).forEach { _ ->
            jpaOutboxRecordRepository.save(
                OutboxRecord.restore(
                    id = UUID.randomUUID().toString(),
                    recordKey = recordKey,
                    payload = "payload",
                    attributes = emptyMap(),
                    partition = 1,
                    createdAt = createdAt,
                    status = status,
                    completedAt = null,
                    failureCount = 0,
                    nextRetryAt = createdAt,
                    handlerId = "handlerId",
                ),
            )
        }
    }

    private fun createRecordWithPartition(
        recordKey: String,
        status: OutboxRecordStatus,
        partition: Int,
        createdAt: OffsetDateTime = OffsetDateTime.now(clock),
    ) {
        jpaOutboxRecordRepository.save(
            OutboxRecord.restore(
                id = UUID.randomUUID().toString(),
                recordKey = recordKey,
                payload = "test-payload",
                attributes = emptyMap(),
                partition = partition,
                createdAt = createdAt,
                status = status,
                completedAt = if (status == COMPLETED) createdAt else null,
                failureCount = 0,
                nextRetryAt = createdAt,
                handlerId = "handlerId",
            ),
        )
    }

    private fun createCompletedRecordsForRecordKey(
        count: Int,
        recordKey: String,
        createdAt: OffsetDateTime = OffsetDateTime.now(clock),
    ) {
        (0 until count).forEach { _ ->
            jpaOutboxRecordRepository.save(
                OutboxRecord.restore(
                    id = UUID.randomUUID().toString(),
                    recordKey = recordKey,
                    payload = "test-payload",
                    attributes = emptyMap(),
                    partition = 1,
                    createdAt = createdAt,
                    status = COMPLETED,
                    completedAt = createdAt,
                    failureCount = 0,
                    nextRetryAt = createdAt,
                    handlerId = "handlerId",
                ),
            )
        }
    }

    private fun createRecordWithPartitionAndTime(
        recordKey: String,
        status: OutboxRecordStatus,
        partition: Int,
        createdAt: OffsetDateTime,
    ): OutboxRecord<String> {
        val record =
            OutboxRecord.restore(
                id = UUID.randomUUID().toString(),
                recordKey = recordKey,
                payload = "payload",
                attributes = emptyMap(),
                partition = partition,
                createdAt = createdAt,
                status = status,
                completedAt = null,
                failureCount = 0,
                nextRetryAt = createdAt,
                handlerId = "handlerId",
            )

        jpaOutboxRecordRepository.save(record)
        return record
    }

    @EnableOutbox
    @SpringBootApplication
    class TestApplication
}
