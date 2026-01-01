package io.namastack.outbox

import io.namastack.outbox.annotation.EnableOutbox
import io.namastack.outbox.handler.OutboxFailureContext
import io.namastack.outbox.handler.OutboxHandlerWithFallback
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.OutboxTypedHandlerWithFallback
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
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit.SECONDS

/**
 * Integration test for interface-based fallback handlers.
 *
 * Tests OutboxHandlerWithFallback and OutboxTypedHandlerWithFallback interfaces
 * to verify fallback behavior on retry exhaustion and non-retryable exceptions.
 */
@OutboxIntegrationTest
@Import(
    InterfaceBasedFallbackIntegrationTest.GenericHandlerWithFallback::class,
    InterfaceBasedFallbackIntegrationTest.TypedHandlerWithFallback::class,
    InterfaceBasedFallbackIntegrationTest.FallbackWithSuccessCompletion::class,
    InterfaceBasedFallbackIntegrationTest.FallbackWithException::class,
)
class InterfaceBasedFallbackIntegrationTest {
    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var recordRepository: OutboxRecordRepository

    @Autowired
    private lateinit var outbox: Outbox

    @AfterEach
    fun cleanup() {
        handledEvents.clear()
        fallbackCalls.clear()
        cleanupTables()
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `generic handler with fallback on retry exhaustion`() {
        outbox.schedule(GenericFailureEvent("will-fail"), "key1")

        await()
            .atMost(15, SECONDS)
            .untilAsserted {
                // 3 handler calls = 1 initial attempt + 2 retries (max-retries: 2)
                assertThat(handledEvents["GenericHandlerWithFallback"]).hasSize(3)
                assertThat(fallbackCalls["GenericHandlerWithFallback"]).hasSize(1)
                assertThat(recordRepository.findFailedRecords()).isEmpty()

                val context = fallbackCalls["GenericHandlerWithFallback"]?.first()
                assertThat(context?.retriesExhausted).isTrue()
                assertThat(context?.failureCount).isEqualTo(3)
                assertThat(context?.recordKey).isEqualTo("key1")
            }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `typed handler with fallback on retry exhaustion`() {
        outbox.schedule(TypedFailureEvent("typed-fail"), "key2")

        await()
            .atMost(15, SECONDS)
            .untilAsserted {
                // 3 handler calls = 1 initial attempt + 2 retries (max-retries: 2)
                assertThat(handledEvents["TypedHandlerWithFallback"]).hasSize(3)
                assertThat(fallbackCalls["TypedHandlerWithFallback"]).hasSize(1)
                assertThat(recordRepository.findFailedRecords()).isEmpty()

                val context = fallbackCalls["TypedHandlerWithFallback"]?.first()
                assertThat(context?.retriesExhausted).isTrue()
                assertThat(context?.nonRetryableException).isFalse()
            }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `successful fallback marks record as completed`() {
        outbox.schedule(SuccessfulFallbackEvent("success-fallback"), "key3")

        await()
            .atMost(15, SECONDS)
            .untilAsserted {
                // 3 handler calls = 1 initial attempt + 2 retries (max-retries: 2)
                assertThat(handledEvents["FallbackWithSuccessCompletion"]).hasSize(3)
                assertThat(fallbackCalls["FallbackWithSuccessCompletion"]).hasSize(1)
                assertThat(recordRepository.findFailedRecords()).isEmpty()
            }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `fallback exception marks record as failed`() {
        outbox.schedule(FallbackExceptionEvent("fallback-throws"), "key4")

        await()
            .atMost(15, SECONDS)
            .untilAsserted {
                // 3 handler calls = 1 initial attempt + 2 retries (max-retries: 2)
                assertThat(handledEvents["FallbackWithException"]).hasSize(3)
                assertThat(fallbackCalls["FallbackWithException"]).hasSize(1)
                assertThat(recordRepository.findFailedRecords()).hasSize(1)
            }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `fallback context contains correct failure information`() {
        outbox.schedule(GenericFailureEvent("context-check"), "key5")

        await()
            .atMost(15, SECONDS)
            .untilAsserted {
                val context = fallbackCalls["GenericHandlerWithFallback"]?.first()
                assertThat(context).isNotNull
                assertThat(context?.recordId).isNotBlank()
                assertThat(context?.recordKey).isEqualTo("key5")
                assertThat(context?.failureCount).isEqualTo(3)
                assertThat(context?.lastFailure).isInstanceOf(RuntimeException::class.java)
                assertThat(context?.lastFailure?.message).contains("context-check")
                assertThat(context?.handlerId).isNotBlank()
                assertThat(context?.createdAt).isNotNull()
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
    data class GenericFailureEvent(
        val value: String,
    )

    data class TypedFailureEvent(
        val value: String,
    )

    data class SuccessfulFallbackEvent(
        val value: String,
    )

    data class FallbackExceptionEvent(
        val value: String,
    )

    // Test Handlers
    @Component
    class GenericHandlerWithFallback : OutboxHandlerWithFallback {
        override fun handle(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) {
            handledEvents.computeIfAbsent("GenericHandlerWithFallback") { mutableListOf() }.add(payload)
            throw RuntimeException("Handler failure for: $payload")
        }

        override fun handleFailure(
            payload: Any,
            context: OutboxFailureContext,
        ) {
            fallbackCalls.computeIfAbsent("GenericHandlerWithFallback") { mutableListOf() }.add(context)
        }
    }

    @Component
    class TypedHandlerWithFallback : OutboxTypedHandlerWithFallback<TypedFailureEvent> {
        override fun handle(
            payload: TypedFailureEvent,
            metadata: OutboxRecordMetadata,
        ) {
            handledEvents.computeIfAbsent("TypedHandlerWithFallback") { mutableListOf() }.add(payload)
            throw RuntimeException("Typed handler failure")
        }

        override fun handleFailure(
            payload: TypedFailureEvent,
            context: OutboxFailureContext,
        ) {
            fallbackCalls.computeIfAbsent("TypedHandlerWithFallback") { mutableListOf() }.add(context)
        }
    }

    @Component
    class FallbackWithSuccessCompletion : OutboxTypedHandlerWithFallback<SuccessfulFallbackEvent> {
        override fun handle(
            payload: SuccessfulFallbackEvent,
            metadata: OutboxRecordMetadata,
        ) {
            handledEvents.computeIfAbsent("FallbackWithSuccessCompletion") { mutableListOf() }.add(payload)
            throw RuntimeException("Handler failure")
        }

        override fun handleFailure(
            payload: SuccessfulFallbackEvent,
            context: OutboxFailureContext,
        ) {
            fallbackCalls.computeIfAbsent("FallbackWithSuccessCompletion") { mutableListOf() }.add(context)
            // Successful fallback - no exception
        }
    }

    @Component
    class FallbackWithException : OutboxTypedHandlerWithFallback<FallbackExceptionEvent> {
        override fun handle(
            payload: FallbackExceptionEvent,
            metadata: OutboxRecordMetadata,
        ) {
            handledEvents.computeIfAbsent("FallbackWithException") { mutableListOf() }.add(payload)
            throw RuntimeException("Handler failure")
        }

        override fun handleFailure(
            payload: FallbackExceptionEvent,
            context: OutboxFailureContext,
        ) {
            fallbackCalls.computeIfAbsent("FallbackWithException") { mutableListOf() }.add(context)
            throw RuntimeException("Fallback also fails")
        }
    }

    companion object {
        val handledEvents = ConcurrentHashMap<String, MutableList<Any>>()
        val fallbackCalls = ConcurrentHashMap<String, MutableList<OutboxFailureContext>>()
    }

    @EnableOutbox
    @EnableScheduling
    @SpringBootApplication
    class TestApplication
}
