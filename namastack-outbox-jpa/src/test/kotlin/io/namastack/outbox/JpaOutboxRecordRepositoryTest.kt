package io.namastack.outbox

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.OutboxRecordStatus.COMPLETED
import io.namastack.outbox.OutboxRecordStatus.FAILED
import io.namastack.outbox.OutboxRecordStatus.NEW
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.test.context.ContextConfiguration
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.time.temporal.ChronoUnit.MINUTES
import java.util.UUID

@DataJpaTest
@ImportAutoConfiguration(JpaOutboxAutoConfiguration::class, OutboxJacksonAutoConfiguration::class)
@ContextConfiguration(classes = [JpaOutboxRecordRepositoryTest.TestApplication::class])
class JpaOutboxRecordRepositoryTest {
    private val clock: Clock = Clock.systemDefaultZone()
    private val retentionBase: Instant = Instant.parse("2026-01-01T12:00:00Z")

    @Autowired
    private lateinit var jpaOutboxRecordRepository: JpaOutboxRecordRepository

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    private lateinit var entityManager: EntityManager

    @Nested
    @DisplayName("save()")
    inner class SaveTests {
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

            jpaOutboxRecordRepository.save(updatedRecord)

            val persistedUpdatedRecord = jpaOutboxRecordRepository.findIncompleteRecordsByRecordKey(recordKey).first()

            assertThat(persistedUpdatedRecord.failureCount).isEqualTo(updatedRecord.failureCount)
        }
    }

    @Nested
    @DisplayName("findPendingRecords()")
    inner class FindPendingRecordsTests {
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
            val now = Instant.now(clock)
            val recordKey = UUID.randomUUID().toString()

            createNewRecordsForRecordKey(1, recordKey, NEW, now.minus(1, MINUTES))
            createNewRecordsForRecordKey(1, recordKey, NEW, now.minus(2, MINUTES))
            createNewRecordsForRecordKey(1, recordKey, NEW, now.minus(3, MINUTES))

            val records = jpaOutboxRecordRepository.findPendingRecords()

            assertThat(records).hasSize(3)
            assertThat(records.map { it.createdAt })
                .containsExactly(
                    now.minus(3, MINUTES),
                    now.minus(2, MINUTES),
                    now.minus(1, MINUTES),
                )
        }

        @Test
        fun `finds pending records returns empty when none exist`() {
            createCompletedRecords(2)
            createFailedRecords(1)

            val records = jpaOutboxRecordRepository.findPendingRecords()

            assertThat(records).isEmpty()
        }
    }

    @Nested
    @DisplayName("findCompletedRecords()")
    inner class FindCompletedRecordsTests {
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
        fun `finds completed records returns empty when none exist`() {
            createNewRecords(2)
            createFailedRecords(1)

            val records = jpaOutboxRecordRepository.findCompletedRecords()

            assertThat(records).isEmpty()
        }
    }

    @Nested
    @DisplayName("findCompletedRecords(completedBefore, limit)")
    inner class FindCompletedRecordsBeforeTests {
        @Test
        fun `returns completed records completed before cutoff oldest first`() {
            val newest = saveRetentionRecord(COMPLETED, completedAt = retentionBase.minusSeconds(10))
            val oldest = saveRetentionRecord(COMPLETED, completedAt = retentionBase.minusSeconds(30))
            val middle = saveRetentionRecord(COMPLETED, completedAt = retentionBase.minusSeconds(20))
            saveRetentionRecord(COMPLETED, completedAt = retentionBase.plusSeconds(10))
            saveRetentionRecord(FAILED, nextRetryAt = retentionBase.minusSeconds(60))
            saveRetentionRecord(NEW, createdAt = retentionBase.minusSeconds(60))

            val ids = jpaOutboxRecordRepository.findCompletedRecords(retentionBase, 10).map { it.id }

            assertThat(ids).containsExactly(oldest, middle, newest)
        }

        @Test
        fun `excludes records completed exactly at cutoff`() {
            val older = saveRetentionRecord(COMPLETED, completedAt = retentionBase.minusSeconds(1))
            saveRetentionRecord(COMPLETED, completedAt = retentionBase)

            val ids = jpaOutboxRecordRepository.findCompletedRecords(retentionBase, 10).map { it.id }

            assertThat(ids).containsExactly(older)
        }

        @Test
        fun `returns at most limit records`() {
            val oldest = saveRetentionRecord(COMPLETED, completedAt = retentionBase.minusSeconds(30))
            val middle = saveRetentionRecord(COMPLETED, completedAt = retentionBase.minusSeconds(20))
            saveRetentionRecord(COMPLETED, completedAt = retentionBase.minusSeconds(10))

            val ids = jpaOutboxRecordRepository.findCompletedRecords(retentionBase, 2).map { it.id }

            assertThat(ids).containsExactly(oldest, middle)
        }

        @Test
        fun `rejects non positive limit`() {
            assertThatThrownBy { jpaOutboxRecordRepository.findCompletedRecords(retentionBase, 0) }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { jpaOutboxRecordRepository.findCompletedRecords(retentionBase, -1) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Nested
    @DisplayName("findFailedRecords()")
    inner class FindFailedRecordsTests {
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
        fun `finds failed records returns empty when none exist`() {
            createNewRecords(2)
            createCompletedRecords(1)

            val records = jpaOutboxRecordRepository.findFailedRecords()

            assertThat(records).isEmpty()
        }
    }

    @Nested
    @DisplayName("findFailedRecords(lastRetryBefore, limit)")
    inner class FindFailedRecordsBeforeTests {
        @Test
        fun `returns failed records with next retry before cutoff oldest first`() {
            val newer = saveRetentionRecord(FAILED, nextRetryAt = retentionBase.minusSeconds(10))
            val older = saveRetentionRecord(FAILED, nextRetryAt = retentionBase.minusSeconds(20))
            saveRetentionRecord(FAILED, nextRetryAt = retentionBase.plusSeconds(10))
            saveRetentionRecord(COMPLETED, completedAt = retentionBase.minusSeconds(60))
            saveRetentionRecord(NEW, createdAt = retentionBase.minusSeconds(60))

            val ids = jpaOutboxRecordRepository.findFailedRecords(retentionBase, 10).map { it.id }

            assertThat(ids).containsExactly(older, newer)
        }

        @Test
        fun `uses next retry time instead of creation time`() {
            val createdFirstRetriedLast =
                saveRetentionRecord(
                    FAILED,
                    createdAt = retentionBase.minusSeconds(3600),
                    nextRetryAt = retentionBase.minusSeconds(10),
                )
            val createdLastRetriedFirst =
                saveRetentionRecord(
                    FAILED,
                    createdAt = retentionBase.minusSeconds(1800),
                    nextRetryAt = retentionBase.minusSeconds(20),
                )
            saveRetentionRecord(
                FAILED,
                createdAt = retentionBase.minusSeconds(3600),
                nextRetryAt = retentionBase.plusSeconds(10),
            )

            val ids = jpaOutboxRecordRepository.findFailedRecords(retentionBase, 10).map { it.id }

            assertThat(ids).containsExactly(createdLastRetriedFirst, createdFirstRetriedLast)
        }

        @Test
        fun `excludes records with next retry exactly at cutoff`() {
            val older = saveRetentionRecord(FAILED, nextRetryAt = retentionBase.minusSeconds(1))
            saveRetentionRecord(FAILED, nextRetryAt = retentionBase)

            val ids = jpaOutboxRecordRepository.findFailedRecords(retentionBase, 10).map { it.id }

            assertThat(ids).containsExactly(older)
        }

        @Test
        fun `returns at most limit records`() {
            val oldest = saveRetentionRecord(FAILED, nextRetryAt = retentionBase.minusSeconds(30))
            val middle = saveRetentionRecord(FAILED, nextRetryAt = retentionBase.minusSeconds(20))
            saveRetentionRecord(FAILED, nextRetryAt = retentionBase.minusSeconds(10))

            val ids = jpaOutboxRecordRepository.findFailedRecords(retentionBase, 2).map { it.id }

            assertThat(ids).containsExactly(oldest, middle)
        }

        @Test
        fun `rejects non positive limit`() {
            assertThatThrownBy { jpaOutboxRecordRepository.findFailedRecords(retentionBase, 0) }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { jpaOutboxRecordRepository.findFailedRecords(retentionBase, -1) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Nested
    @DisplayName("findIncompleteRecordsByRecordKey()")
    inner class FindIncompleteRecordsByRecordKeyTests {
        @Test
        fun `finds all incomplete records by record key ordered by created date`() {
            val recordKey = UUID.randomUUID().toString()
            val now = Instant.now(clock)

            createNewRecordsForRecordKey(1, recordKey, NEW, now.minus(3, MINUTES))
            createNewRecordsForRecordKey(1, recordKey, NEW, now.minus(1, MINUTES))
            createNewRecordsForRecordKey(1, recordKey, NEW, now.minus(2, MINUTES))
            createNewRecordsForRecordKey(1, "other-record-key", NEW, now)

            val records = jpaOutboxRecordRepository.findIncompleteRecordsByRecordKey(recordKey)

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

            val records = jpaOutboxRecordRepository.findIncompleteRecordsByRecordKey(recordKey)

            assertThat(records).isEmpty()
        }
    }

    @Nested
    @DisplayName("findRecordKeysInPartitions()")
    inner class FindRecordKeysInPartitionsTests {
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
        fun `processes oldest record when multiple NEW records exist`() {
            val recordKey = UUID.randomUUID().toString()
            val now = Instant.now(clock)

            createRecordWithPartitionAndTime(recordKey, NEW, 1, now.minus(3, MINUTES))
            createRecordWithPartitionAndTime(recordKey, NEW, 1, now.minus(1, MINUTES))

            val result = jpaOutboxRecordRepository.findRecordKeysInPartitions(setOf(1), NEW, 10, true)

            assertThat(result).contains(recordKey)
        }

        @Test
        fun `allows processing when oldest record is ready`() {
            val recordKey = UUID.randomUUID().toString()
            val now = Instant.now(clock)

            createRecordWithPartitionAndTime(recordKey, NEW, 1, now.minus(3, MINUTES))

            val result = jpaOutboxRecordRepository.findRecordKeysInPartitions(setOf(1), NEW, 10, true)

            assertThat(result).contains(recordKey)
        }

        @Test
        fun `orders by oldest record creation time across partitions`() {
            val now = Instant.now(clock)

            val recordKey1 = UUID.randomUUID().toString()
            val recordKey2 = UUID.randomUUID().toString()
            val recordKey3 = UUID.randomUUID().toString()

            createRecordWithPartitionAndTime(recordKey2, NEW, 1, now.minus(2, MINUTES))
            createRecordWithPartitionAndTime(recordKey1, NEW, 2, now.minus(3, MINUTES))
            createRecordWithPartitionAndTime(recordKey3, NEW, 1, now.minus(1, MINUTES))

            val result = jpaOutboxRecordRepository.findRecordKeysInPartitions(setOf(1, 2), NEW, 10, true)

            assertThat(result).containsExactly(recordKey1, recordKey2, recordKey3)
        }
    }

    @Nested
    @DisplayName("countRecordsByPartition()")
    inner class CountRecordsByPartitionTests {
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
    }

    @Nested
    @DisplayName("countRecordsByPartitions()")
    inner class CountRecordsByPartitionsTests {
        @Test
        fun `counts records across selected partitions and status including future retries`() {
            createRecordWithPartition(UUID.randomUUID().toString(), NEW, 1)
            createRecordWithPartition(UUID.randomUUID().toString(), NEW, 1)
            createRecordWithPartition(UUID.randomUUID().toString(), NEW, 2, Instant.now(clock).plus(10, MINUTES))
            createRecordWithPartition(UUID.randomUUID().toString(), FAILED, 1)
            createRecordWithPartition(UUID.randomUUID().toString(), COMPLETED, 2)
            createRecordWithPartition(UUID.randomUUID().toString(), NEW, 3)

            assertThat(jpaOutboxRecordRepository.countRecordsByPartitions(setOf(1, 2), NEW)).isEqualTo(3)
            assertThat(jpaOutboxRecordRepository.countRecordsByPartitions(setOf(1, 2), FAILED)).isEqualTo(1)
            assertThat(jpaOutboxRecordRepository.countRecordsByPartitions(setOf(1, 2), COMPLETED)).isEqualTo(1)
            assertThat(jpaOutboxRecordRepository.countRecordsByPartitions(setOf(2), NEW)).isEqualTo(1)
            assertThat(jpaOutboxRecordRepository.countRecordsByPartitions(setOf(4), NEW)).isZero()
        }

        @Test
        fun `counts zero records for empty partitions`() {
            createRecordWithPartition(UUID.randomUUID().toString(), NEW, 1)

            assertThat(jpaOutboxRecordRepository.countRecordsByPartitions(emptySet(), NEW)).isZero()
        }
    }

    @Nested
    @DisplayName("countByStatus()")
    inner class CountByStatusTests {
        @Test
        fun `counts records by status`() {
            createNewRecords(1)
            createCompletedRecords(2)
            createFailedRecords(3)

            assertThat(jpaOutboxRecordRepository.countByStatus(NEW)).isEqualTo(1)
            assertThat(jpaOutboxRecordRepository.countByStatus(COMPLETED)).isEqualTo(2)
            assertThat(jpaOutboxRecordRepository.countByStatus(FAILED)).isEqualTo(3)
        }
    }

    @Nested
    @DisplayName("deleteByStatus()")
    inner class DeleteByStatusTests {
        @Test
        fun `deletes records by status`() {
            createNewRecords()
            createFailedRecords()

            jpaOutboxRecordRepository.deleteByStatus(NEW)
            assertThat(jpaOutboxRecordRepository.countByStatus(NEW)).isEqualTo(0)
            assertThat(jpaOutboxRecordRepository.countByStatus(FAILED)).isEqualTo(3)
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
    }

    @Nested
    @DisplayName("deleteByRecordKeyAndStatus()")
    inner class DeleteByRecordKeyAndStatusTests {
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
    }

    @Nested
    @DisplayName("deleteById()")
    inner class DeleteByIdTests {
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
    }

    @Nested
    @DisplayName("deleteByIds()")
    inner class DeleteByIdsTests {
        @Test
        fun `deletes records with given ids and ignores unknown ids`() {
            val completed = saveRetentionRecord(COMPLETED, completedAt = retentionBase)
            val failed = saveRetentionRecord(FAILED, nextRetryAt = retentionBase)
            val pending = saveRetentionRecord(NEW)
            val kept = saveRetentionRecord(COMPLETED, completedAt = retentionBase)

            val deleted = jpaOutboxRecordRepository.deleteByIds(listOf(completed, failed, pending, "unknown-id"))

            assertThat(deleted).isEqualTo(3)
            assertThat(jpaOutboxRecordRepository.findCompletedRecords().map { it.id }).containsExactly(kept)
            assertThat(jpaOutboxRecordRepository.countByStatus(FAILED)).isZero()
            assertThat(jpaOutboxRecordRepository.countByStatus(NEW)).isZero()
        }

        @Test
        fun `deletes nothing for empty ids`() {
            saveRetentionRecord(COMPLETED, completedAt = retentionBase)

            assertThat(jpaOutboxRecordRepository.deleteByIds(emptyList())).isZero()
            assertThat(jpaOutboxRecordRepository.countByStatus(COMPLETED)).isEqualTo(1)
        }
    }

    @Nested
    @DisplayName("deleteCompletedRecords(completedBefore, limit)")
    inner class DeleteCompletedRecordsTests {
        @Test
        fun `deletes completed records before cutoff up to limit oldest first`() {
            saveRetentionRecord(COMPLETED, completedAt = retentionBase.minusSeconds(30))
            saveRetentionRecord(COMPLETED, completedAt = retentionBase.minusSeconds(20))
            val newest = saveRetentionRecord(COMPLETED, completedAt = retentionBase.minusSeconds(10))
            val recent = saveRetentionRecord(COMPLETED, completedAt = retentionBase.plusSeconds(10))
            saveRetentionRecord(FAILED, nextRetryAt = retentionBase.minusSeconds(60))
            saveRetentionRecord(NEW, createdAt = retentionBase.minusSeconds(60))

            assertThat(jpaOutboxRecordRepository.deleteCompletedRecords(retentionBase, 2)).isEqualTo(2)
            val remaining = jpaOutboxRecordRepository.findCompletedRecords().map { it.id }
            assertThat(remaining).containsExactlyInAnyOrder(newest, recent)

            assertThat(jpaOutboxRecordRepository.deleteCompletedRecords(retentionBase, 2)).isEqualTo(1)
            assertThat(jpaOutboxRecordRepository.deleteCompletedRecords(retentionBase, 2)).isZero()
            assertThat(jpaOutboxRecordRepository.findCompletedRecords().map { it.id }).containsExactly(recent)
            assertThat(jpaOutboxRecordRepository.countByStatus(FAILED)).isEqualTo(1)
            assertThat(jpaOutboxRecordRepository.countByStatus(NEW)).isEqualTo(1)
        }

        @Test
        fun `keeps records completed exactly at cutoff`() {
            val atCutoff = saveRetentionRecord(COMPLETED, completedAt = retentionBase)

            assertThat(jpaOutboxRecordRepository.deleteCompletedRecords(retentionBase, 10)).isZero()
            assertThat(jpaOutboxRecordRepository.findCompletedRecords().map { it.id }).containsExactly(atCutoff)
        }

        @Test
        fun `returns zero when nothing is eligible`() {
            saveRetentionRecord(NEW, createdAt = retentionBase.minusSeconds(60))
            saveRetentionRecord(FAILED, nextRetryAt = retentionBase.minusSeconds(60))

            assertThat(jpaOutboxRecordRepository.deleteCompletedRecords(retentionBase, 10)).isZero()
            assertThat(jpaOutboxRecordRepository.countByStatus(NEW)).isEqualTo(1)
            assertThat(jpaOutboxRecordRepository.countByStatus(FAILED)).isEqualTo(1)
        }

        @Test
        fun `deletes records whose payload type no longer exists`() {
            val id = saveRetentionRecord(COMPLETED, completedAt = retentionBase.minusSeconds(10))
            replaceRecordTypeWithMissingClass(id)

            assertThat(jpaOutboxRecordRepository.deleteCompletedRecords(retentionBase, 10)).isEqualTo(1)
            assertThat(jpaOutboxRecordRepository.countByStatus(COMPLETED)).isZero()
        }

        @Test
        fun `rejects non positive limit`() {
            assertThatThrownBy { jpaOutboxRecordRepository.deleteCompletedRecords(retentionBase, 0) }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { jpaOutboxRecordRepository.deleteCompletedRecords(retentionBase, -1) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Nested
    @DisplayName("deleteFailedRecords(lastRetryBefore, limit)")
    inner class DeleteFailedRecordsTests {
        @Test
        fun `deletes failed records with next retry before cutoff up to limit oldest first`() {
            saveRetentionRecord(FAILED, nextRetryAt = retentionBase.minusSeconds(30))
            saveRetentionRecord(FAILED, nextRetryAt = retentionBase.minusSeconds(20))
            val newest = saveRetentionRecord(FAILED, nextRetryAt = retentionBase.minusSeconds(10))
            val retriedRecently =
                saveRetentionRecord(
                    FAILED,
                    createdAt = retentionBase.minusSeconds(3600),
                    nextRetryAt = retentionBase.plusSeconds(10),
                )
            saveRetentionRecord(COMPLETED, completedAt = retentionBase.minusSeconds(60))
            saveRetentionRecord(NEW, createdAt = retentionBase.minusSeconds(60))

            assertThat(jpaOutboxRecordRepository.deleteFailedRecords(retentionBase, 2)).isEqualTo(2)
            val remaining = jpaOutboxRecordRepository.findFailedRecords().map { it.id }
            assertThat(remaining).containsExactlyInAnyOrder(newest, retriedRecently)

            assertThat(jpaOutboxRecordRepository.deleteFailedRecords(retentionBase, 2)).isEqualTo(1)
            assertThat(jpaOutboxRecordRepository.deleteFailedRecords(retentionBase, 2)).isZero()
            assertThat(jpaOutboxRecordRepository.findFailedRecords().map { it.id }).containsExactly(retriedRecently)
            assertThat(jpaOutboxRecordRepository.countByStatus(COMPLETED)).isEqualTo(1)
            assertThat(jpaOutboxRecordRepository.countByStatus(NEW)).isEqualTo(1)
        }

        @Test
        fun `keeps records with next retry exactly at cutoff`() {
            val atCutoff = saveRetentionRecord(FAILED, nextRetryAt = retentionBase)

            assertThat(jpaOutboxRecordRepository.deleteFailedRecords(retentionBase, 10)).isZero()
            assertThat(jpaOutboxRecordRepository.findFailedRecords().map { it.id }).containsExactly(atCutoff)
        }

        @Test
        fun `returns zero when nothing is eligible`() {
            saveRetentionRecord(NEW, createdAt = retentionBase.minusSeconds(60))
            saveRetentionRecord(COMPLETED, completedAt = retentionBase.minusSeconds(60))

            assertThat(jpaOutboxRecordRepository.deleteFailedRecords(retentionBase, 10)).isZero()
            assertThat(jpaOutboxRecordRepository.countByStatus(NEW)).isEqualTo(1)
            assertThat(jpaOutboxRecordRepository.countByStatus(COMPLETED)).isEqualTo(1)
        }

        @Test
        fun `deletes records whose payload type no longer exists`() {
            val id = saveRetentionRecord(FAILED, nextRetryAt = retentionBase.minusSeconds(10))
            replaceRecordTypeWithMissingClass(id)

            assertThat(jpaOutboxRecordRepository.deleteFailedRecords(retentionBase, 10)).isEqualTo(1)
            assertThat(jpaOutboxRecordRepository.countByStatus(FAILED)).isZero()
        }

        @Test
        fun `releases record key barrier for later records`() {
            val recordKey = UUID.randomUUID().toString()
            saveRetentionRecord(
                FAILED,
                recordKey = recordKey,
                createdAt = retentionBase.minusSeconds(60),
                nextRetryAt = retentionBase.minusSeconds(60),
            )
            saveRetentionRecord(NEW, recordKey = recordKey, createdAt = retentionBase.minusSeconds(30))

            val keysBeforeDelete = jpaOutboxRecordRepository.findRecordKeysInPartitions(setOf(1), NEW, 10, true)
            assertThat(jpaOutboxRecordRepository.deleteFailedRecords(retentionBase, 10)).isEqualTo(1)
            val keysAfterDelete = jpaOutboxRecordRepository.findRecordKeysInPartitions(setOf(1), NEW, 10, true)

            assertThat(keysBeforeDelete).doesNotContain(recordKey)
            assertThat(keysAfterDelete).containsExactly(recordKey)
        }

        @Test
        fun `rejects non positive limit`() {
            assertThatThrownBy { jpaOutboxRecordRepository.deleteFailedRecords(retentionBase, 0) }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { jpaOutboxRecordRepository.deleteFailedRecords(retentionBase, -1) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    private fun createFailedRecords(count: Int = 3) {
        val now = Instant.now(clock)
        (0 until count).forEach { _ ->
            jpaOutboxRecordRepository.save(
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
            jpaOutboxRecordRepository.save(
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
            jpaOutboxRecordRepository.save(
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
            jpaOutboxRecordRepository.save(
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
        jpaOutboxRecordRepository.save(
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
            jpaOutboxRecordRepository.save(
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

        jpaOutboxRecordRepository.save(record)
        return record
    }

    private fun saveRetentionRecord(
        status: OutboxRecordStatus,
        recordKey: String = UUID.randomUUID().toString(),
        createdAt: Instant = retentionBase.minusSeconds(7200),
        completedAt: Instant? = null,
        nextRetryAt: Instant = createdAt,
    ): String {
        val record =
            OutboxRecord.restore(
                id = UUID.randomUUID().toString(),
                recordKey = recordKey,
                payload = "payload",
                context = emptyMap(),
                partition = 1,
                createdAt = createdAt,
                status = status,
                completedAt = completedAt,
                failureCount = 0,
                failureReason = null,
                nextRetryAt = nextRetryAt,
                handlerId = "handlerId",
                failureException = null,
            )
        jpaOutboxRecordRepository.save(record)
        return record.id
    }

    private fun replaceRecordTypeWithMissingClass(id: String) {
        entityManager
            .createNativeQuery("UPDATE outbox_record SET record_type = 'com.example.RemovedEvent' WHERE id = :id")
            .setParameter("id", id)
            .executeUpdate()
    }

    @SpringBootApplication
    class TestApplication
}
