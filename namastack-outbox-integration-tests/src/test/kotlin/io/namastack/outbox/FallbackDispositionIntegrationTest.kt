package io.namastack.outbox

import io.namastack.outbox.annotation.OutboxFallbackHandler
import io.namastack.outbox.annotation.OutboxHandler
import io.namastack.outbox.handler.OutboxFailureContext
import io.namastack.outbox.handler.OutboxFallbackDisposition
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.OutboxTypedHandlerWithFallback
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Import
import org.springframework.stereotype.Component
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit.SECONDS

/**
 * Integration test for [OutboxFallbackDisposition.KEEP_FAILED].
 *
 * Scenario:
 * - Schedules two records for the same key: a record whose handler always fails, then a record
 *   that would succeed
 * - The fallback of the first record returns normally with [OutboxFallbackDisposition.KEEP_FAILED]
 * - Verifies that the first record stays FAILED with its original failure reason and the second
 *   record remains blocked
 */
@OutboxIntegrationTest
@Import(
    FallbackDispositionIntegrationTest.AnnotatedKeepFailedHandler::class,
    FallbackDispositionIntegrationTest.InterfaceKeepFailedHandler::class,
)
@TestPropertySource(properties = ["namastack.outbox.processing.stop-on-first-failure=true"])
class FallbackDispositionIntegrationTest {
    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var recordRepository: OutboxRecordRepository

    @Autowired
    private lateinit var outbox: Outbox

    @BeforeEach
    fun resetRecordedCalls() {
        handledEvents.clear()
        fallbackCalls.clear()
    }

    @AfterEach
    fun cleanup() {
        cleanupTables()
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `annotated fallback with KEEP_FAILED keeps the key blocked`() {
        transactionTemplate.executeWithoutResult {
            outbox.schedule(AnnotatedEvent("e1", fail = true), "annotated-key")
            outbox.schedule(AnnotatedEvent("e2", fail = false), "annotated-key")
        }

        assertKeyStaysBlocked("AnnotatedKeepFailedHandler", "e1")
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `interface fallback with KEEP_FAILED keeps the key blocked`() {
        transactionTemplate.executeWithoutResult {
            outbox.schedule(InterfaceEvent("e1", fail = true), "interface-key")
            outbox.schedule(InterfaceEvent("e2", fail = false), "interface-key")
        }

        assertKeyStaysBlocked("InterfaceKeepFailedHandler", "e1")
    }

    private fun assertKeyStaysBlocked(
        handler: String,
        failingValue: String,
    ) {
        await()
            .atMost(15, SECONDS)
            .untilAsserted {
                assertThat(fallbackCalls[handler]).hasSize(1)
                assertThat(recordRepository.findFailedRecords()).singleElement().satisfies({
                    assertThat(it.failureReason).isEqualTo("Delivery failed for $failingValue")
                })
            }

        await()
            .during(1, SECONDS)
            .atMost(2, SECONDS)
            .untilAsserted {
                // 3 handler calls = 1 initial attempt + 2 retries (max-retries: 2), all for the failing record
                assertThat(handledEvents[handler]).containsOnly(failingValue).hasSize(3)
                assertThat(fallbackCalls[handler]).hasSize(1)
                assertThat(recordRepository.findCompletedRecords()).isEmpty()
                assertThat(recordRepository.findFailedRecords()).hasSize(1)
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

    // Test Events
    data class AnnotatedEvent(
        val value: String,
        val fail: Boolean,
    )

    data class InterfaceEvent(
        val value: String,
        val fail: Boolean,
    )

    // Test Handlers
    @Component
    class AnnotatedKeepFailedHandler {
        @OutboxHandler
        fun handle(payload: AnnotatedEvent) {
            handledEvents.computeIfAbsent("AnnotatedKeepFailedHandler") { mutableListOf() }.add(payload.value)
            if (payload.fail) throw RuntimeException("Delivery failed for ${payload.value}")
        }

        @OutboxFallbackHandler(disposition = OutboxFallbackDisposition.KEEP_FAILED)
        fun handleFailure(
            payload: AnnotatedEvent,
            context: OutboxFailureContext,
        ) {
            fallbackCalls.computeIfAbsent("AnnotatedKeepFailedHandler") { mutableListOf() }.add(context)
        }
    }

    @Component
    class InterfaceKeepFailedHandler : OutboxTypedHandlerWithFallback<InterfaceEvent> {
        override fun handle(
            payload: InterfaceEvent,
            metadata: OutboxRecordMetadata,
        ) {
            handledEvents.computeIfAbsent("InterfaceKeepFailedHandler") { mutableListOf() }.add(payload.value)
            if (payload.fail) throw RuntimeException("Delivery failed for ${payload.value}")
        }

        override fun handleFailure(
            payload: InterfaceEvent,
            context: OutboxFailureContext,
        ) {
            fallbackCalls.computeIfAbsent("InterfaceKeepFailedHandler") { mutableListOf() }.add(context)
        }

        override fun getTypedFallbackDisposition() = OutboxFallbackDisposition.KEEP_FAILED
    }

    companion object {
        val handledEvents = ConcurrentHashMap<String, MutableList<String>>()
        val fallbackCalls = ConcurrentHashMap<String, MutableList<OutboxFailureContext>>()
    }

    @SpringBootApplication
    class TestApplication
}
