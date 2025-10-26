package io.namastack.outbox

import io.namastack.outbox.OutboxRecordStatus.COMPLETED
import io.namastack.outbox.OutboxRecordStatus.FAILED
import io.namastack.outbox.OutboxRecordStatus.NEW
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
                .build(clock)

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
                .build(clock)

        jpaOutboxRecordRepository.save(record)

        val updatedRecord =
            OutboxRecord.restore(
                id = record.id,
                aggregateId = record.aggregateId,
                eventType = record.eventType,
                payload = record.payload,
                partition = 1,
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
    fun `returns pending records ordered by createdAt asc`() {
        val now = OffsetDateTime.now(clock)
        val aggregateId = UUID.randomUUID().toString()

        createNewRecordsForAggregateId(1, aggregateId, NEW, now.minusMinutes(1))
        createNewRecordsForAggregateId(1, aggregateId, NEW, now.minusMinutes(2))
        createNewRecordsForAggregateId(1, aggregateId, NEW, now.minusMinutes(3))

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
    fun `finds aggregate ids with pending records`() {
        val aggregateId1 = UUID.randomUUID().toString()
        val aggregateId2 = UUID.randomUUID().toString()
        createNewRecordsForAggregateId(3, aggregateId1, NEW)
        createNewRecordsForAggregateId(3, aggregateId2, NEW)
        createFailedRecords(3)
        createCompletedRecords(3)

        val aggregateIds = jpaOutboxRecordRepository.findAggregateIdsWithPendingRecords(NEW, 10)

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

    @Test
    fun `finds all incomplete records by aggregate id ordered by created date`() {
        val aggregateId = UUID.randomUUID().toString()
        val now = OffsetDateTime.now(clock)

        createNewRecordsForAggregateId(1, aggregateId, NEW, now.minusMinutes(3))
        createNewRecordsForAggregateId(1, aggregateId, NEW, now.minusMinutes(1))
        createNewRecordsForAggregateId(1, aggregateId, FAILED, now.minusMinutes(2))
        createNewRecordsForAggregateId(1, "other-aggregate", NEW, now)

        val records = jpaOutboxRecordRepository.findAllIncompleteRecordsByAggregateId(aggregateId)

        assertThat(records).hasSize(3)
        assertThat(records.map { it.createdAt }).containsExactly(
            now.minusMinutes(3),
            now.minusMinutes(2),
            now.minusMinutes(1),
        )
        assertThat(records.map { it.aggregateId }).allMatch { it == aggregateId }
    }

    @Test
    fun `finds aggregate ids with pending records respects batch size`() {
        val aggregateIds = (1..5).map { UUID.randomUUID().toString() }
        aggregateIds.forEach { createNewRecordsForAggregateId(1, it, NEW) }

        val result = jpaOutboxRecordRepository.findAggregateIdsWithPendingRecords(NEW, 3)

        assertThat(result).hasSize(3)
        assertThat(result).allMatch { it in aggregateIds }
    }

    @Test
    fun `finds aggregate ids in partitions`() {
        val partition1AggregateId = UUID.randomUUID().toString()
        val partition2AggregateId = UUID.randomUUID().toString()

        createRecordWithPartition(partition1AggregateId, NEW, 1)
        createRecordWithPartition(partition2AggregateId, NEW, 2)

        val partition1Aggregates =
            jpaOutboxRecordRepository.findAggregateIdsInPartitions(
                listOf(1),
                NEW,
                10,
            )

        val partition2Aggregates =
            jpaOutboxRecordRepository.findAggregateIdsInPartitions(
                listOf(2),
                NEW,
                10,
            )

        assertThat(partition1Aggregates).containsExactly(partition1AggregateId)
        assertThat(partition2Aggregates).containsExactly(partition2AggregateId)
    }

    @Test
    fun `finds aggregate ids in multiple partitions`() {
        val aggregateId1 = UUID.randomUUID().toString()
        val aggregateId2 = UUID.randomUUID().toString()
        val aggregateId3 = UUID.randomUUID().toString()

        createRecordWithPartition(aggregateId1, NEW, 1)
        createRecordWithPartition(aggregateId2, NEW, 2)
        createRecordWithPartition(aggregateId3, NEW, 3)

        val aggregateIds =
            jpaOutboxRecordRepository.findAggregateIdsInPartitions(
                listOf(1, 2),
                NEW,
                10,
            )

        assertThat(aggregateIds).containsExactlyInAnyOrder(aggregateId1, aggregateId2)
        assertThat(aggregateIds).doesNotContain(aggregateId3)
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
    fun `finds records by partition with status filter`() {
        val aggregateId1 = UUID.randomUUID().toString()
        val aggregateId2 = UUID.randomUUID().toString()
        val aggregateId3 = UUID.randomUUID().toString()

        createRecordWithPartition(aggregateId1, NEW, 1)
        createRecordWithPartition(aggregateId2, FAILED, 1)
        createRecordWithPartition(aggregateId3, NEW, 2)

        val newRecordsPartition1 = jpaOutboxRecordRepository.findRecordsByPartition(1, NEW)
        val failedRecordsPartition1 = jpaOutboxRecordRepository.findRecordsByPartition(1, FAILED)

        assertThat(newRecordsPartition1).hasSize(1)
        assertThat(newRecordsPartition1.first().aggregateId).isEqualTo(aggregateId1)
        assertThat(failedRecordsPartition1).hasSize(1)
        assertThat(failedRecordsPartition1.first().aggregateId).isEqualTo(aggregateId2)
    }

    @Test
    fun `finds records by partition without status filter`() {
        val aggregateId1 = UUID.randomUUID().toString()
        val aggregateId2 = UUID.randomUUID().toString()

        createRecordWithPartition(aggregateId1, NEW, 1)
        createRecordWithPartition(aggregateId2, FAILED, 1)

        val allRecordsPartition1 = jpaOutboxRecordRepository.findRecordsByPartition(1)

        assertThat(allRecordsPartition1).hasSize(2)
        assertThat(allRecordsPartition1.map { it.aggregateId }).containsExactlyInAnyOrder(aggregateId1, aggregateId2)
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
    fun `finds incomplete records by aggregate id returns empty when none exist`() {
        val aggregateId = UUID.randomUUID().toString()
        createCompletedRecordsForAggregateId(2, aggregateId)

        val records = jpaOutboxRecordRepository.findAllIncompleteRecordsByAggregateId(aggregateId)

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
    fun `deletes records by aggregate id and status only affects specified combination`() {
        val targetAggregateId = UUID.randomUUID().toString()
        val otherAggregateId = UUID.randomUUID().toString()

        createNewRecordsForAggregateId(2, targetAggregateId, NEW)
        createNewRecordsForAggregateId(1, targetAggregateId, FAILED)
        createNewRecordsForAggregateId(1, otherAggregateId, NEW)
        createNewRecordsForAggregateId(1, otherAggregateId, FAILED)

        jpaOutboxRecordRepository.deleteByAggregateIdAndStatus(targetAggregateId, NEW)

        assertThat(jpaOutboxRecordRepository.findAllIncompleteRecordsByAggregateId(targetAggregateId)).hasSize(1)
        assertThat(jpaOutboxRecordRepository.findAllIncompleteRecordsByAggregateId(otherAggregateId)).hasSize(2)
    }

    @Test
    fun `findAggregateIdsWithPendingRecords processes oldest record when multiple NEW records exist`() {
        val aggregateId = UUID.randomUUID().toString()
        val now = OffsetDateTime.now(clock)

        createRecordWithAggregateAndTime(aggregateId, NEW, now.minusMinutes(3))
        createRecordWithAggregateAndTime(aggregateId, NEW, now.minusMinutes(1))

        val result = jpaOutboxRecordRepository.findAggregateIdsWithPendingRecords(NEW, 10)

        assertThat(result).contains(aggregateId)
    }

    @Test
    fun `findAggregateIdsWithPendingRecords allows processing when oldest record is ready`() {
        val aggregateId = UUID.randomUUID().toString()
        val now = OffsetDateTime.now(clock)

        createRecordWithAggregateAndTime(aggregateId, NEW, now.minusMinutes(3))

        val result = jpaOutboxRecordRepository.findAggregateIdsWithPendingRecords(NEW, 10)

        assertThat(result).contains(aggregateId)
    }

    @Test
    fun `findAggregateIdsWithPendingRecords allows processing after older record completed`() {
        val aggregateId = UUID.randomUUID().toString()
        val now = OffsetDateTime.now(clock)

        // Create an older FAILED record that blocks processing
        val olderFailedRecord =
            OutboxRecord.restore(
                id = UUID.randomUUID().toString(),
                aggregateId = aggregateId,
                eventType = "eventType",
                payload = "payload",
                partition = 1,
                createdAt = now.minusMinutes(5),
                status = FAILED,
                completedAt = null, // Still incomplete, blocks processing
                retryCount = 3,
                nextRetryAt = now.minusMinutes(5),
            )
        jpaOutboxRecordRepository.save(olderFailedRecord)

        // Create a newer NEW record that should be blocked
        createRecordWithAggregateAndTime(aggregateId, NEW, now.minusMinutes(1))

        val result1 = jpaOutboxRecordRepository.findAggregateIdsWithPendingRecords(NEW, 10)
        assertThat(result1).doesNotContain(aggregateId)

        // Complete the older record
        val completedRecord =
            OutboxRecord.restore(
                id = olderFailedRecord.id,
                aggregateId = aggregateId,
                eventType = "eventType",
                payload = "payload",
                partition = 1,
                createdAt = now.minusMinutes(5),
                status = COMPLETED,
                completedAt = now,
                retryCount = 3,
                nextRetryAt = now.minusMinutes(5),
            )
        jpaOutboxRecordRepository.save(completedRecord)

        val result2 = jpaOutboxRecordRepository.findAggregateIdsWithPendingRecords(NEW, 10)
        assertThat(result2).contains(aggregateId)
    }

    @Test
    fun `findAggregateIdsWithPendingRecords handles failed older records correctly`() {
        val aggregateId = UUID.randomUUID().toString()
        val now = OffsetDateTime.now(clock)

        // Create an older FAILED record (incomplete, so blocks processing)
        val failedRecord =
            OutboxRecord.restore(
                id = UUID.randomUUID().toString(),
                aggregateId = aggregateId,
                eventType = "eventType",
                payload = "payload",
                partition = 1,
                createdAt = now.minusMinutes(5),
                status = FAILED,
                completedAt = null, // Still incomplete!
                retryCount = 3,
                nextRetryAt = now.minusMinutes(5),
            )
        jpaOutboxRecordRepository.save(failedRecord)

        // Create a newer NEW record that should be blocked by the failed record
        createRecordWithAggregateAndTime(aggregateId, NEW, now.minusMinutes(1))

        val result = jpaOutboxRecordRepository.findAggregateIdsWithPendingRecords(NEW, 10)
        assertThat(result).doesNotContain(aggregateId)
    }

    @Test
    fun `findAggregateIdsWithPendingRecords respects nextRetryAt timing`() {
        val aggregateId = UUID.randomUUID().toString()
        val now = OffsetDateTime.now(clock)

        createRecordWithRetryTime(aggregateId, NEW, now.plusMinutes(5))

        val result = jpaOutboxRecordRepository.findAggregateIdsWithPendingRecords(NEW, 10)

        assertThat(result).doesNotContain(aggregateId)
    }

    @Test
    fun `findAggregateIdsWithPendingRecords allows processing when nextRetryAt is past`() {
        val aggregateId = UUID.randomUUID().toString()
        val now = OffsetDateTime.now(clock)

        createRecordWithRetryTime(aggregateId, NEW, now.minusMinutes(1))

        val result = jpaOutboxRecordRepository.findAggregateIdsWithPendingRecords(NEW, 10)

        assertThat(result).contains(aggregateId)
    }

    @Test
    fun `findAggregateIdsWithPendingRecords handles mixed aggregate scenarios correctly`() {
        val now = OffsetDateTime.now(clock)

        val readyAggregate = UUID.randomUUID().toString()
        createRecordWithAggregateAndTime(readyAggregate, NEW, now.minusMinutes(1))

        val multipleRecordsAggregate = UUID.randomUUID().toString()
        createRecordWithAggregateAndTime(multipleRecordsAggregate, NEW, now.minusMinutes(3))
        createRecordWithAggregateAndTime(multipleRecordsAggregate, NEW, now.minusMinutes(1))

        val notReadyAggregate = UUID.randomUUID().toString()
        createRecordWithRetryTime(notReadyAggregate, NEW, now.plusMinutes(5))

        val exactlyNowAggregate = UUID.randomUUID().toString()
        createRecordWithRetryTime(exactlyNowAggregate, NEW, now)

        val result = jpaOutboxRecordRepository.findAggregateIdsWithPendingRecords(NEW, 10)

        assertThat(result).contains(readyAggregate)
        assertThat(result).contains(multipleRecordsAggregate)
        assertThat(result).contains(exactlyNowAggregate)
        assertThat(result).doesNotContain(notReadyAggregate)
    }

    @Test
    fun `findAggregateIdsWithPendingRecords orders by oldest record creation time`() {
        val now = OffsetDateTime.now(clock)

        val aggregate1 = UUID.randomUUID().toString()
        val aggregate2 = UUID.randomUUID().toString()
        val aggregate3 = UUID.randomUUID().toString()

        createRecordWithAggregateAndTime(aggregate2, NEW, now.minusMinutes(2))
        createRecordWithAggregateAndTime(aggregate1, NEW, now.minusMinutes(3))
        createRecordWithAggregateAndTime(aggregate3, NEW, now.minusMinutes(1))

        val result = jpaOutboxRecordRepository.findAggregateIdsWithPendingRecords(NEW, 10)

        assertThat(result).containsExactly(aggregate1, aggregate2, aggregate3)
    }

    @Test
    fun `findAggregateIdsInPartitions processes oldest record when multiple NEW records exist`() {
        val aggregateId = UUID.randomUUID().toString()
        val now = OffsetDateTime.now(clock)

        createRecordWithPartitionAndTime(aggregateId, NEW, 1, now.minusMinutes(3))
        createRecordWithPartitionAndTime(aggregateId, NEW, 1, now.minusMinutes(1))

        val result = jpaOutboxRecordRepository.findAggregateIdsInPartitions(listOf(1), NEW, 10)

        assertThat(result).contains(aggregateId)
    }

    @Test
    fun `findAggregateIdsInPartitions allows processing when oldest record is ready`() {
        val aggregateId = UUID.randomUUID().toString()
        val now = OffsetDateTime.now(clock)

        createRecordWithPartitionAndTime(aggregateId, NEW, 1, now.minusMinutes(3))

        val result = jpaOutboxRecordRepository.findAggregateIdsInPartitions(listOf(1), NEW, 10)

        assertThat(result).contains(aggregateId)
    }

    @Test
    fun `findAggregateIdsInPartitions orders by oldest record creation time across partitions`() {
        val now = OffsetDateTime.now(clock)

        val aggregate1 = UUID.randomUUID().toString()
        val aggregate2 = UUID.randomUUID().toString()
        val aggregate3 = UUID.randomUUID().toString()

        createRecordWithPartitionAndTime(aggregate2, NEW, 1, now.minusMinutes(2))
        createRecordWithPartitionAndTime(aggregate1, NEW, 2, now.minusMinutes(3))
        createRecordWithPartitionAndTime(aggregate3, NEW, 1, now.minusMinutes(1))

        val result = jpaOutboxRecordRepository.findAggregateIdsInPartitions(listOf(1, 2), NEW, 10)

        assertThat(result).containsExactly(aggregate1, aggregate2, aggregate3)
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
                    partition = 1,
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
                    partition = 1,
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
                    partition = 1,
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
        createdAt: OffsetDateTime = OffsetDateTime.now(clock),
    ) {
        (0 until count).forEach { _ ->
            jpaOutboxRecordRepository.save(
                OutboxRecord.restore(
                    id = UUID.randomUUID().toString(),
                    aggregateId = aggregateId,
                    eventType = "eventType",
                    payload = "payload",
                    partition = 1,
                    createdAt = createdAt,
                    status = status,
                    completedAt = null,
                    retryCount = 0,
                    nextRetryAt = createdAt,
                ),
            )
        }
    }

    private fun createRecordWithPartition(
        aggregateId: String,
        status: OutboxRecordStatus,
        partition: Int,
        createdAt: OffsetDateTime = OffsetDateTime.now(clock),
    ) {
        jpaOutboxRecordRepository.save(
            OutboxRecord.restore(
                id = UUID.randomUUID().toString(),
                aggregateId = aggregateId,
                eventType = "TestEvent",
                payload = "test-payload",
                partition = partition,
                createdAt = createdAt,
                status = status,
                completedAt = if (status == COMPLETED) createdAt else null,
                retryCount = 0,
                nextRetryAt = createdAt,
            ),
        )
    }

    private fun createCompletedRecordsForAggregateId(
        count: Int,
        aggregateId: String,
        createdAt: OffsetDateTime = OffsetDateTime.now(clock),
    ) {
        (0 until count).forEach { _ ->
            jpaOutboxRecordRepository.save(
                OutboxRecord.restore(
                    id = UUID.randomUUID().toString(),
                    aggregateId = aggregateId,
                    eventType = "TestEvent",
                    payload = "test-payload",
                    partition = 1,
                    createdAt = createdAt,
                    status = COMPLETED,
                    completedAt = createdAt,
                    retryCount = 0,
                    nextRetryAt = createdAt,
                ),
            )
        }
    }

    private fun createRecordWithAggregateAndTime(
        aggregateId: String,
        status: OutboxRecordStatus,
        createdAt: OffsetDateTime,
    ): OutboxRecord {
        val record =
            OutboxRecord.restore(
                id = UUID.randomUUID().toString(),
                aggregateId = aggregateId,
                eventType = "eventType",
                payload = "payload",
                partition = 1,
                createdAt = createdAt,
                status = status,
                completedAt = null,
                retryCount = 0,
                nextRetryAt = createdAt,
            )

        jpaOutboxRecordRepository.save(record)
        return record
    }

    private fun createRecordWithRetryTime(
        aggregateId: String,
        status: OutboxRecordStatus,
        nextRetryAt: OffsetDateTime,
    ): OutboxRecord {
        val record =
            OutboxRecord.restore(
                id = UUID.randomUUID().toString(),
                aggregateId = aggregateId,
                eventType = "eventType",
                payload = "payload",
                partition = 1,
                createdAt = OffsetDateTime.now(clock),
                status = status,
                completedAt = null,
                retryCount = 0,
                nextRetryAt = nextRetryAt,
            )

        jpaOutboxRecordRepository.save(record)
        return record
    }

    private fun createRecordWithPartitionAndTime(
        aggregateId: String,
        status: OutboxRecordStatus,
        partition: Int,
        createdAt: OffsetDateTime,
    ): OutboxRecord {
        val record =
            OutboxRecord.restore(
                id = UUID.randomUUID().toString(),
                aggregateId = aggregateId,
                eventType = "eventType",
                payload = "payload",
                partition = partition,
                createdAt = createdAt,
                status = status,
                completedAt = null,
                retryCount = 0,
                nextRetryAt = createdAt,
            )

        jpaOutboxRecordRepository.save(record)
        return record
    }

    @EnableOutbox
    @SpringBootApplication
    class TestApplication
}
