package io.namastack.outbox

import io.namastack.outbox.annotation.EnableOutbox
import io.namastack.outbox.handler.OutboxHandler
import io.namastack.outbox.handler.OutboxRecordMetadata
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Import
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.stereotype.Component
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
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
@TestPropertySource(properties = ["outbox.processing.stop-on-first-failure=true"])
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
        override fun handle(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) {
            if (payload == "failure") {
                throw RuntimeException("failure")
            }
        }
    }

    @EnableOutbox
    @EnableScheduling
    @SpringBootApplication
    class TestApplication
}
