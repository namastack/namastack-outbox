package io.namastack.outbox

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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Integration test for graceful shutdown behavior.
 *
 * Verifies that:
 * - Shutdown waits for the current processing cycle to complete
 * - Records being processed are fully handled before shutdown completes
 * - The shutdownTimeoutSeconds property is respected
 */
@OutboxIntegrationTest
@Import(GracefulShutdownIntegrationTest.SlowTestHandler::class)
@TestPropertySource(
    properties = [
        "namastack.outbox.polling.fixed.interval=100",
        "namastack.outbox.processing.shutdown-timeout-seconds=10",
    ],
)
class GracefulShutdownIntegrationTest {
    private val clock: Clock = Clock.systemDefaultZone()

    @Autowired
    private lateinit var outboxRecordRepository: OutboxRecordRepository

    @Autowired
    private lateinit var outboxProcessingScheduler: OutboxProcessingScheduler

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var slowTestHandler: SlowTestHandler

    @AfterEach
    fun cleanup() {
        slowTestHandler.reset()
        cleanupTables()
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `shutdown waits for current processing cycle to complete`() {
        // Given: A record that takes time to process
        createRecord("slow-key", "slow-payload")

        // Wait for processing to start
        await()
            .atMost(5, TimeUnit.SECONDS)
            .until { slowTestHandler.processingStarted.get() }

        // When: Initiate shutdown while processing is in progress
        val shutdownCompleted = AtomicBoolean(false)
        val shutdownThread =
            Thread {
                outboxProcessingScheduler.unregisterJob()
                shutdownCompleted.set(true)
            }
        shutdownThread.start()

        // Give shutdown a moment to start waiting
        Thread.sleep(100)

        // Then: Shutdown should be blocked (waiting for processing)
        assertThat(shutdownCompleted.get()).isFalse()

        // Allow handler to complete
        slowTestHandler.canComplete.countDown()

        // Wait for shutdown to complete
        shutdownThread.join(5000)

        // Verify: Handler completed successfully and shutdown finished
        assertThat(slowTestHandler.processingCompleted.get()).isTrue()
        assertThat(shutdownCompleted.get()).isTrue()

        // Verify: Record was fully processed
        await()
            .atMost(2, TimeUnit.SECONDS)
            .untilAsserted {
                assertThat(outboxRecordRepository.findCompletedRecords()).hasSize(1)
            }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `process skips new cycles after shutdown is initiated`() {
        // Given: Shutdown has been initiated (no records yet)
        outboxProcessingScheduler.unregisterJob()

        // When: Create a record after shutdown
        createRecord("new-key", "new-payload")

        // Then: process() should skip execution
        outboxProcessingScheduler.process()

        // Verify: Record was NOT processed (still NEW status)
        val records = outboxRecordRepository.findIncompleteRecordsByRecordKey("new-key")
        assertThat(records).hasSize(1)
        assertThat(records.first().status).isEqualTo(OutboxRecordStatus.NEW)
    }

    private fun cleanupTables() {
        transactionTemplate.executeWithoutResult {
            entityManager.createQuery("DELETE FROM OutboxRecordEntity").executeUpdate()
            entityManager.createQuery("DELETE FROM OutboxInstanceEntity").executeUpdate()
            entityManager.createQuery("DELETE FROM OutboxPartitionAssignmentEntity").executeUpdate()
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
                    "io.namastack.outbox.GracefulShutdownIntegrationTest\$SlowTestHandler#handle(java.lang.Object,io.namastack.outbox.handler.OutboxRecordMetadata)",
                ).build(clock),
        )

    /**
     * Test handler that blocks until signaled to complete.
     * Used to simulate slow processing for shutdown testing.
     */
    @Component
    class SlowTestHandler : OutboxHandler {
        val processingStarted = AtomicBoolean(false)
        val processingCompleted = AtomicBoolean(false)
        val canComplete = CountDownLatch(1)

        override fun handle(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) {
            processingStarted.set(true)

            // Wait until test signals we can complete
            canComplete.await(30, TimeUnit.SECONDS)

            processingCompleted.set(true)
        }

        fun reset() {
            processingStarted.set(false)
            processingCompleted.set(false)
        }
    }

    @EnableScheduling
    @SpringBootApplication
    class TestApplication
}
