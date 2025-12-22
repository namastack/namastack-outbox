package io.namastack.outbox

import io.namastack.outbox.annotation.EnableOutbox
import io.namastack.outbox.annotation.OutboxFallbackHandler
import io.namastack.outbox.annotation.OutboxHandler
import io.namastack.outbox.handler.OutboxFailureContext
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
 * Integration test for multiple annotation-based handlers with their own fallbacks.
 */
@OutboxIntegrationTest
@Import(AnnotationMultipleFallbacksIntegrationTest.MultipleHandlersWithFallbacks::class)
class AnnotationMultipleFallbacksIntegrationTest {
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
    fun `multiple handlers with their own fallbacks`() {
        outbox.schedule(Event1("event1"), "key1")
        outbox.schedule(Event2("event2"), "key2")

        await()
            .atMost(15, SECONDS)
            .untilAsserted {
                // Each handler: 3 calls = 1 initial attempt + 2 retries (max-retries: 2)
                assertThat(handledEvents["Handler1"]).hasSize(3)
                assertThat(handledEvents["Handler2"]).hasSize(3)
                assertThat(fallbackCalls["Fallback1"]).hasSize(1)
                assertThat(fallbackCalls["Fallback2"]).hasSize(1)
                assertThat(recordRepository.findCompletedRecords()).hasSize(2)
                assertThat(recordRepository.findFailedRecords()).isEmpty()
            }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `handler with multiple matching fallbacks uses first one`() {
        outbox.schedule(Event1("multi-match"), "key3")

        await()
            .atMost(15, SECONDS)
            .untilAsserted {
                // Only first matching fallback should be called for Event1
                assertThat(fallbackCalls["Fallback1"]).hasSize(1)
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

    data class Event1(
        val value: String,
    )

    data class Event2(
        val value: String,
    )

    @Component
    class MultipleHandlersWithFallbacks {
        @OutboxHandler
        fun handleEvent1(payload: Event1) {
            handledEvents.computeIfAbsent("Handler1") { mutableListOf() }.add(payload)
            throw RuntimeException("Handler 1 failure")
        }

        @OutboxHandler
        fun handleEvent2(payload: Event2) {
            handledEvents.computeIfAbsent("Handler2") { mutableListOf() }.add(payload)
            throw RuntimeException("Handler 2 failure")
        }

        @OutboxFallbackHandler
        fun fallback1(
            payload: Event1,
            context: OutboxFailureContext,
        ) {
            fallbackCalls.computeIfAbsent("Fallback1") { mutableListOf() }.add(context)
        }

        @OutboxFallbackHandler
        fun fallback2(
            payload: Event2,
            context: OutboxFailureContext,
        ) {
            fallbackCalls.computeIfAbsent("Fallback2") { mutableListOf() }.add(context)
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
