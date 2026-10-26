package io.namastack.outbox

import io.namastack.outbox.handler.OutboxHandler
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.partition.PartitionHasher
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Import
import org.springframework.stereotype.Component
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS

/**
 * Integration test for ordered outbox processing with stop-on-first-failure enabled.
 *
 * Scenario:
 * - Inserts three records for the same record key: [failure, success, success]
 * - Verifies that only the first (failure) is processed and the others remain unprocessed.
 */
@OutboxIntegrationTest
@Import(OrderedProcessingIntegrationTest.TestProcessor::class)
@TestPropertySource(properties = ["namastack.outbox.processing.stop-on-first-failure=true"])
class OrderedProcessingIntegrationTest {
    private val clock: Clock = Clock.systemDefaultZone()

    @Autowired
    private lateinit var outboxRecordRepository: OutboxRecordRepository

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    private lateinit var entityManager: EntityManager

    @AfterEach
    fun cleanup() = cleanupTables()

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `should process only first record and stop on failure`() {
        val recordKey = "record-key-1"
        createRecord(recordKey, "failure")
        createRecord(recordKey, "success")
        createRecord(recordKey, "success")

        await()
            .atMost(10, SECONDS)
            .untilAsserted {
                assertThat(outboxRecordRepository.findCompletedRecords()).isEmpty()
                assertThat(outboxRecordRepository.findFailedRecords()).hasSize(1)
            }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `failed handler blocks tied sibling and subsequent event across polling cycles`() {
        val createdAt = Instant.now(clock).minusSeconds(60)
        val failedId = "00000000-0000-4000-8000-000000000001"
        val siblingId = "00000000-0000-4000-8000-000000000002"
        val laterId = "00000000-0000-4000-8000-000000000003"
        val failureHandlerId =
            "${TestProcessor::class.java.name}#handle(java.lang.Object,${OutboxRecordMetadata::class.java.name})"

        // Commit all deliveries together, with insertion order opposite to processing order.
        transactionTemplate.executeWithoutResult {
            saveOrderedRecord(laterId, "success", "ordered-success", createdAt.plusSeconds(1))
            saveOrderedRecord(siblingId, "success", "ordered-success", createdAt)
            saveOrderedRecord(failedId, "failure", failureHandlerId, createdAt)
        }

        await().atMost(10, SECONDS).untilAsserted {
            assertThat(outboxRecordRepository.findFailedRecords().map { it.id }).containsExactly(failedId)
        }

        // Polling runs every 50 ms; the block must survive subsequent polls after permanent failure.
        await().during(500, MILLISECONDS).atMost(5, SECONDS).untilAsserted {
            assertThat(outboxRecordRepository.findCompletedRecords()).isEmpty()
            assertThat(outboxRecordRepository.findPendingRecords().map { it.id })
                .containsExactly(siblingId, laterId)
        }
    }

    private fun saveOrderedRecord(
        id: String,
        payload: String,
        handlerId: String,
        createdAt: Instant,
    ) {
        outboxRecordRepository.save(
            OutboxRecord.restore(
                id = id,
                recordKey = "tied-handler-key",
                payload = payload,
                context = emptyMap(),
                partition = PartitionHasher.getPartitionForRecordKey("tied-handler-key"),
                createdAt = createdAt,
                status = OutboxRecordStatus.NEW,
                completedAt = null,
                failureCount = 0,
                failureException = null,
                failureReason = null,
                nextRetryAt = createdAt,
                handlerId = handlerId,
            ),
        )
    }

    private fun cleanupTables() {
        transactionTemplate.executeWithoutResult {
            entityManager.createQuery("DELETE FROM OutboxRecordEntity").executeUpdate()
            entityManager.createQuery("DELETE FROM OutboxInstanceEntity").executeUpdate()
            entityManager.createQuery("DELETE FROM OutboxPartitionAssignmentEntity ").executeUpdate()
            entityManager.flush()
            entityManager.clear()
        }
    }

    private fun createRecord(
        recordKey: String,
        payload: String,
    ): OutboxRecord<String> =
        outboxRecordRepository.save(
            OutboxRecord
                .Builder<String>()
                .key(recordKey)
                .payload(payload)
                .handlerId(
                    @Suppress("ktlint:standard:max-line-length")
                    $$"io.namastack.outbox.OrderedProcessingIntegrationTest$TestProcessor#handle(java.lang.Object,io.namastack.outbox.handler.OutboxRecordMetadata)",
                ).build(clock),
        )

    /**
     * Test processor that throws an exception for records with payload "failure".
     */
    @Component
    class TestProcessor : OutboxHandler {
        @io.namastack.outbox.annotation.OutboxHandler(id = "ordered-success")
        fun handleSuccess(payload: String) {
            check(payload == "success")
        }

        override fun handle(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) {
            if (payload == "failure") {
                throw RuntimeException("failure")
            }
        }
    }

    @SpringBootApplication
    class TestApplication
}
