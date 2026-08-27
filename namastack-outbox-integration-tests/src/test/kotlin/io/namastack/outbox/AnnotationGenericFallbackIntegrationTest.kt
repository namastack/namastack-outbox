package io.namastack.outbox

import io.namastack.outbox.annotation.OutboxFallbackHandler
import io.namastack.outbox.annotation.OutboxHandler
import io.namastack.outbox.handler.OutboxFailureContext
import io.namastack.outbox.handler.OutboxRecordMetadata
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Import
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit.SECONDS

/** Verifies annotation-based generic primary and fallback handlers through persistence and processing. */
@OutboxIntegrationTest
@Import(AnnotationGenericFallbackIntegrationTest.GenericAnnotatedHandlerWithFallback::class)
class AnnotationGenericFallbackIntegrationTest {
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
        handlerCalls.clear()
        fallbackCalls.clear()
        transactionTemplate.executeWithoutResult {
            entityManager.createQuery("DELETE FROM OutboxRecordEntity").executeUpdate()
            entityManager.createQuery("DELETE FROM OutboxInstanceEntity").executeUpdate()
            entityManager.createQuery("DELETE FROM OutboxPartitionAssignmentEntity").executeUpdate()
            entityManager.flush()
            entityManager.clear()
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `generic annotated handler invokes matching fallback after retry exhaustion`() {
        outbox.schedule(GenericFailureEvent("generic-failure"), "generic-fallback-key")

        await().atMost(15, SECONDS).untilAsserted {
            assertThat(handlerCalls).hasSize(3)
            assertThat(fallbackCalls).hasSize(1)
            assertThat(recordRepository.findCompletedRecords()).hasSize(1)
            assertThat(recordRepository.findFailedRecords()).isEmpty()

            val context = fallbackCalls.single()
            assertThat(context.handlerId).isEqualTo("annotation-generic-fallback")
            assertThat(context.recordKey).isEqualTo("generic-fallback-key")
            assertThat(context.failureCount).isEqualTo(3)
            assertThat(context.retriesExhausted).isTrue()
            assertThat(context.lastFailure?.message).contains("generic-failure")
        }
    }

    data class GenericFailureEvent(
        val value: String,
    )

    @Component
    class GenericAnnotatedHandlerWithFallback {
        @OutboxHandler(id = "annotation-generic-fallback")
        fun handle(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) {
            check(metadata.handlerId == "annotation-generic-fallback")
            handlerCalls.add(payload)
            throw IllegalStateException("Generic handler failure for ${(payload as GenericFailureEvent).value}")
        }

        @OutboxFallbackHandler
        fun handleFailure(
            payload: Any,
            context: OutboxFailureContext,
        ) {
            check(payload is GenericFailureEvent)
            fallbackCalls.add(context)
        }
    }

    @SpringBootApplication
    class TestApplication

    companion object {
        private val handlerCalls = CopyOnWriteArrayList<Any>()
        private val fallbackCalls = CopyOnWriteArrayList<OutboxFailureContext>()
    }
}
