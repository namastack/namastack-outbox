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
 * Integration test for typed annotation-based fallback handlers.
 * Uses only typed handler to avoid generic handler matching.
 */
@OutboxIntegrationTest
@Import(AnnotationTypedFallbackIntegrationTest.TypedAnnotatedHandlerWithFallback::class)
class AnnotationTypedFallbackIntegrationTest {
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
    fun `typed handler with typed fallback`() {
        outbox.schedule(TypedEvent("typed-fail"), "key1")

        await()
            .atMost(15, SECONDS)
            .untilAsserted {
                // 3 handler calls = 1 initial attempt + 2 retries (max-retries: 2)
                assertThat(handledEvents["TypedHandler"]).hasSize(3)
                assertThat(fallbackCalls["TypedFallback"]).hasSize(1)
                assertThat(recordRepository.findCompletedRecords()).hasSize(1)
                assertThat(recordRepository.findFailedRecords()).isEmpty()

                val context = fallbackCalls["TypedFallback"]?.first()
                assertThat(context?.lastFailure?.message).contains("Typed handler failure")
            }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `fallback context contains correct failure information`() {
        outbox.schedule(TypedEvent("context-check"), "key2")

        await()
            .atMost(15, SECONDS)
            .untilAsserted {
                val context = fallbackCalls["TypedFallback"]?.first()
                assertThat(context).isNotNull
                assertThat(context?.recordId).isNotBlank()
                assertThat(context?.recordKey).isEqualTo("key2")
                assertThat(context?.failureCount).isEqualTo(3)
                assertThat(context?.lastFailure).isInstanceOf(RuntimeException::class.java)
                assertThat(context?.retriesExhausted).isTrue()
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

    data class TypedEvent(
        val value: String,
    )

    @Component
    class TypedAnnotatedHandlerWithFallback {
        @OutboxHandler
        fun handle(payload: TypedEvent) {
            handledEvents.computeIfAbsent("TypedHandler") { mutableListOf() }.add(payload)
            throw RuntimeException("Typed handler failure")
        }

        @OutboxFallbackHandler
        fun handleFailure(
            payload: TypedEvent,
            context: OutboxFailureContext,
        ) {
            fallbackCalls.computeIfAbsent("TypedFallback") { mutableListOf() }.add(context)
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
