package com.beisel.springoutbox

import com.beisel.springoutbox.OutboxRecordStatus.COMPLETED
import com.beisel.springoutbox.OutboxRecordStatus.FAILED
import com.beisel.springoutbox.OutboxRecordStatus.NEW
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import java.time.Clock
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

@DataJpaTest
@ImportAutoConfiguration(JpaOutboxAutoConfiguration::class)
class JpaOutboxRecordRepositoryTest {
    private val clock: Clock = Clock.systemDefaultZone()

    @Autowired
    private lateinit var jpaOutboxRecordRepository: JpaOutboxRecordRepository

    @Test
    fun `saves an entity`() {
        val aggregateId = UUID.randomUUID().toString()
        val record =
            OutboxRecord
                .Builder()
                .aggregateId(aggregateId)
                .eventType("eventType")
                .payload("payload")
                .build()

        jpaOutboxRecordRepository.save(record)

        val persistedRecord = jpaOutboxRecordRepository.findAllIncompleteRecordsByAggregateId(aggregateId).first()

        assertThat(persistedRecord.aggregateId).isEqualTo(record.aggregateId)
        assertThat(persistedRecord.eventType).isEqualTo(record.eventType)
        assertThat(persistedRecord.payload).isEqualTo(record.payload)
        assertThat(persistedRecord.status).isEqualTo(record.status)
        assertThat(persistedRecord.retryCount).isEqualTo(record.retryCount)
        assertThat(persistedRecord.completedAt).isNull()
        assertThat(persistedRecord.createdAt).isCloseTo(record.createdAt, within(1, ChronoUnit.MILLIS))
        assertThat(persistedRecord.nextRetryAt).isCloseTo(record.nextRetryAt, within(1, ChronoUnit.MILLIS))
    }

    @Test
    fun `updates an entity`() {
        val aggregateId = UUID.randomUUID().toString()
        val record =
            OutboxRecord
                .Builder()
                .aggregateId(aggregateId)
                .eventType("eventType")
                .payload("payload")
                .build()

        jpaOutboxRecordRepository.save(record)

        val updatedRecord =
            OutboxRecord.restore(
                id = record.id,
                aggregateId = record.aggregateId,
                eventType = record.eventType,
                payload = record.payload,
                createdAt = record.createdAt,
                status = record.status,
                completedAt = record.completedAt,
                retryCount = record.retryCount + 1,
                nextRetryAt = record.nextRetryAt,
            )

        jpaOutboxRecordRepository.save(updatedRecord)

        val persistedUpdatedRecord =
            jpaOutboxRecordRepository
                .findAllIncompleteRecordsByAggregateId(
                    aggregateId,
                ).first()

        assertThat(persistedUpdatedRecord.retryCount).isEqualTo(updatedRecord.retryCount)
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
    fun `finds aggregate ids with pending records`() {
        val aggregateId1 = UUID.randomUUID().toString()
        val aggregateId2 = UUID.randomUUID().toString()
        createNewRecordsForAggregateId(3, aggregateId1, NEW)
        createNewRecordsForAggregateId(3, aggregateId2, NEW)
        createFailedRecords(3)
        createCompletedRecords(3)

        val aggregateIds = jpaOutboxRecordRepository.findAggregateIdsWithPendingRecords(NEW)

        assertThat(aggregateIds).containsExactlyInAnyOrder(aggregateId1, aggregateId2)
    }

    @Test
    fun `finds aggregate ids with failed records`() {
        val aggregateId1 = UUID.randomUUID().toString()
        val aggregateId2 = UUID.randomUUID().toString()
        createNewRecordsForAggregateId(3, aggregateId1, FAILED)
        createNewRecordsForAggregateId(3, aggregateId2, FAILED)
        createNewRecords(3)
        createCompletedRecords(3)

        val aggregateIds = jpaOutboxRecordRepository.findAggregateIdsWithFailedRecords()

        assertThat(aggregateIds).containsExactlyInAnyOrder(aggregateId1, aggregateId2)
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
    fun `deletes records by status and aggregateId`() {
        val aggregateId1 = UUID.randomUUID().toString()
        val aggregateId2 = UUID.randomUUID().toString()
        createNewRecordsForAggregateId(1, aggregateId1, NEW)
        createNewRecordsForAggregateId(1, aggregateId1, FAILED)
        createNewRecordsForAggregateId(1, aggregateId2, NEW)
        createNewRecordsForAggregateId(1, aggregateId2, FAILED)

        jpaOutboxRecordRepository.deleteByAggregateIdAndStatus(aggregateId1, FAILED)

        assertThat(jpaOutboxRecordRepository.findPendingRecords()).hasSize(2)
        assertThat(jpaOutboxRecordRepository.findFailedRecords()).hasSize(1)
    }

    private fun createFailedRecords(count: Int = 3) {
        val now = OffsetDateTime.now(clock)
        (0 until count).forEach { _ ->
            jpaOutboxRecordRepository.save(
                OutboxRecord.restore(
                    id = UUID.randomUUID().toString(),
                    aggregateId = UUID.randomUUID().toString(),
                    eventType = "eventType",
                    payload = "payload",
                    createdAt = now,
                    status = FAILED,
                    completedAt = null,
                    retryCount = 3,
                    nextRetryAt = now,
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
                    aggregateId = UUID.randomUUID().toString(),
                    eventType = "eventType",
                    payload = "payload",
                    createdAt = now,
                    status = COMPLETED,
                    completedAt = now,
                    retryCount = 0,
                    nextRetryAt = now,
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
                    aggregateId = UUID.randomUUID().toString(),
                    eventType = "eventType",
                    payload = "payload",
                    createdAt = now,
                    status = NEW,
                    completedAt = null,
                    retryCount = 0,
                    nextRetryAt = now,
                ),
            )
        }
    }

    private fun createNewRecordsForAggregateId(
        count: Int = 3,
        aggregateId: String,
        status: OutboxRecordStatus = NEW,
    ) {
        val now = OffsetDateTime.now(clock)
        (0 until count).forEach { _ ->
            jpaOutboxRecordRepository.save(
                OutboxRecord.restore(
                    id = UUID.randomUUID().toString(),
                    aggregateId = aggregateId,
                    eventType = "eventType",
                    payload = "payload",
                    createdAt = now,
                    status = status,
                    completedAt = null,
                    retryCount = 0,
                    nextRetryAt = now,
                ),
            )
        }
    }

    @EnableOutbox
    @SpringBootApplication
    class TestApplication
}
