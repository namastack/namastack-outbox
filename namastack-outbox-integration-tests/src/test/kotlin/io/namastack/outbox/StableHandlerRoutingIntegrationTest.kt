package io.namastack.outbox

import io.namastack.outbox.annotation.OutboxFallbackHandler
import io.namastack.outbox.annotation.OutboxHandler
import io.namastack.outbox.annotation.OutboxRetryable
import io.namastack.outbox.handler.OutboxFailureContext
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.OutboxTypedHandler
import io.namastack.outbox.handler.registry.OutboxHandlerRegistry
import io.namastack.outbox.retry.OutboxRetryPolicy
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit.SECONDS
import io.namastack.outbox.handler.OutboxHandler as GenericOutboxHandler

/** Verifies canonical IDs and compatibility aliases through real scheduling and record processing. */
@OutboxIntegrationTest
@Import(
    StableHandlerRoutingIntegrationTest.StableAnnotatedHandler::class,
    StableHandlerRoutingIntegrationTest.StableTypedInterfaceHandler::class,
    StableHandlerRoutingIntegrationTest.StableGenericInterfaceHandler::class,
    StableHandlerRoutingIntegrationTest.GeneratedCanonicalAliasHandler::class,
    StableHandlerRoutingIntegrationTest.AliasRetryFallbackHandler::class,
    StableHandlerRoutingIntegrationTest.ProxiedStableHandler::class,
    StableHandlerRoutingIntegrationTest.RetryPolicyConfiguration::class,
)
class StableHandlerRoutingIntegrationTest {
    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var recordRepository: OutboxRecordRepository

    @Autowired
    private lateinit var handlerRegistry: OutboxHandlerRegistry

    @Autowired
    private lateinit var outbox: Outbox

    @Autowired
    private lateinit var clock: Clock

    @Autowired
    private lateinit var proxiedStableHandler: ProxiedStableHandler

    @AfterEach
    fun cleanup() {
        invocations.clear()
        fallbackContexts.clear()
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
    fun `annotation and interface stable IDs are persisted and exposed as canonical descriptors`() {
        outbox.schedule(StableAnnotatedEvent("annotation"), "stable-annotation-key")
        outbox.schedule(StableTypedEvent("typed"), "stable-typed-key")
        outbox.schedule(StableGenericEvent("generic"), "stable-generic-key")

        await().atMost(10, SECONDS).untilAsserted {
            assertThat(invocations)
                .extracting<String> { it.handlerId }
                .containsExactlyInAnyOrder("stable-annotation", "stable-typed", "stable-generic")
            assertThat(recordRepository.findCompletedRecords())
                .extracting<String> { it.handlerId }
                .containsExactlyInAnyOrder("stable-annotation", "stable-typed", "stable-generic")
        }

        assertThat(handlerRegistry.findAllHandlerDescriptors())
            .extracting<String> { it.id }
            .contains("stable-annotation", "stable-typed", "stable-generic")
            .doesNotContain("annotation-v1", "typed-v1", "generic-v1")
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `generated method ID routes records after annotation handler adopts stable ID`() {
        val generatedId =
            "${StableAnnotatedHandler::class.java.name}#handle(" +
                "${StableAnnotatedEvent::class.java.name},${OutboxRecordMetadata::class.java.name})"
        saveRecord(StableAnnotatedEvent("legacy-generated"), "legacy-generated-key", generatedId)

        await().atMost(10, SECONDS).untilAsserted {
            assertThat(invocations).containsExactly(Invocation("stable-annotation", "legacy-generated", generatedId))
            assertThat(recordRepository.findCompletedRecords())
                .extracting<String> { it.handlerId }
                .containsExactly(generatedId)
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `unresolvable legacy handler ID is retried with the default policy and fails permanently`() {
        val removedHandlerId = "removed-handler-v1"
        assertThat(handlerRegistry.getHandlerById(removedHandlerId)).isNull()

        saveRecord(StableAnnotatedEvent("orphaned"), "orphaned-key", removedHandlerId)

        await().atMost(10, SECONDS).untilAsserted {
            val failedRecords = recordRepository.findFailedRecords()
            assertThat(failedRecords).hasSize(1)
            val failedRecord = failedRecords.single()

            assertThat(failedRecord.handlerId).isEqualTo(removedHandlerId)
            assertThat(failedRecord.failureCount).isEqualTo(3)
            assertThat(failedRecord.failureReason).isEqualTo("No handler with id $removedHandlerId")
            assertThat(invocations).isEmpty()
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `configured alias without stable ID remains lookup only`() {
        outbox.schedule(GeneratedAliasEvent("canonical"), "generated-canonical-key")

        await().atMost(10, SECONDS).untilAsserted {
            assertThat(invocations).hasSize(1)
        }

        val canonicalId = invocations.single().handlerId
        assertThat(canonicalId).isNotEqualTo("generated-alias")
        assertThat(handlerRegistry.findAllHandlerDescriptors())
            .extracting<String> { it.id }
            .contains(canonicalId)
            .doesNotContain("generated-alias")

        saveRecord(GeneratedAliasEvent("alias"), "generated-alias-key", "generated-alias")

        await().atMost(10, SECONDS).untilAsserted {
            assertThat(invocations)
                .extracting<String> { it.handlerId }
                .containsExactlyInAnyOrder(canonicalId, "generated-alias")
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `alias resolves primary retry policy and fallback as one registration`() {
        saveRecord(AliasFailureEvent("alias-failure"), "alias-failure-key", "orders-v1")

        await().atMost(10, SECONDS).untilAsserted {
            assertThat(invocations)
                .containsExactly(
                    Invocation("orders-v2", "alias-failure", "orders-v1"),
                    Invocation("orders-v2", "alias-failure", "orders-v1"),
                )
            assertThat(fallbackContexts).hasSize(1)
            assertThat(fallbackContexts.single().handlerId).isEqualTo("orders-v1")
            assertThat(fallbackContexts.single().failureCount).isEqualTo(2)
            assertThat(fallbackContexts.single().retriesExhausted).isTrue()
            assertThat(recordRepository.findCompletedRecords())
                .extracting<String> { it.handlerId }
                .containsExactly("orders-v1")
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `runtime proxy ID routes to stable handler without becoming a descriptor`() {
        val runtimeId =
            "${proxiedStableHandler::class.java.name}#handle(" +
                "${ProxiedEvent::class.java.name},${OutboxRecordMetadata::class.java.name})"
        assertThat(runtimeId).isNotEqualTo("proxied-stable")
        saveRecord(ProxiedEvent("proxied"), "proxy-key", runtimeId)

        await().atMost(10, SECONDS).untilAsserted {
            assertThat(invocations).containsExactly(Invocation("proxied-stable", "proxied", runtimeId))
        }

        assertThat(handlerRegistry.findAllHandlerDescriptors())
            .extracting<String> { it.id }
            .contains("proxied-stable")
            .doesNotContain(runtimeId)
    }

    private fun saveRecord(
        payload: Any,
        key: String,
        handlerId: String,
    ) {
        transactionTemplate.executeWithoutResult {
            recordRepository.save(
                OutboxRecord
                    .Builder<Any>()
                    .key(key)
                    .payload(payload)
                    .handlerId(handlerId)
                    .build(clock),
            )
        }
    }

    data class StableAnnotatedEvent(
        val value: String,
    )

    data class StableTypedEvent(
        val value: String,
    )

    data class StableGenericEvent(
        val value: String,
    )

    data class GeneratedAliasEvent(
        val value: String,
    )

    data class AliasFailureEvent(
        val value: String,
    )

    data class ProxiedEvent(
        val value: String,
    )

    @Component
    class StableAnnotatedHandler {
        @OutboxHandler(id = "stable-annotation", aliases = ["annotation-v1"])
        fun handle(
            payload: StableAnnotatedEvent,
            metadata: OutboxRecordMetadata,
        ) = record("stable-annotation", payload.value, metadata.handlerId)
    }

    @Component
    class StableTypedInterfaceHandler : OutboxTypedHandler<StableTypedEvent> {
        override fun getHandlerId() = "stable-typed"

        override fun getHandlerAliases() = setOf("typed-v1")

        override fun handle(
            payload: StableTypedEvent,
            metadata: OutboxRecordMetadata,
        ) = record("stable-typed", payload.value, metadata.handlerId)
    }

    @Component
    class StableGenericInterfaceHandler : GenericOutboxHandler {
        override fun getHandlerId() = "stable-generic"

        override fun getHandlerAliases() = setOf("generic-v1")

        override fun supports(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) = payload is StableGenericEvent

        override fun handle(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) = record("stable-generic", (payload as StableGenericEvent).value, metadata.handlerId)
    }

    @Component
    class GeneratedCanonicalAliasHandler {
        @OutboxHandler(aliases = ["generated-alias"])
        fun handle(
            payload: GeneratedAliasEvent,
            metadata: OutboxRecordMetadata,
        ) = record("generated-canonical", payload.value, metadata.handlerId)
    }

    @Component
    class AliasRetryFallbackHandler {
        @OutboxHandler(id = "orders-v2", aliases = ["orders-v1"])
        @OutboxRetryable(name = "aliasRetryPolicy")
        fun handle(
            payload: AliasFailureEvent,
            metadata: OutboxRecordMetadata,
        ) {
            record("orders-v2", payload.value, metadata.handlerId)
            throw IllegalStateException("Alias failure for ${payload.value}")
        }

        @OutboxFallbackHandler
        fun handleFailure(
            payload: AliasFailureEvent,
            context: OutboxFailureContext,
        ) {
            check(payload.value == "alias-failure")
            fallbackContexts.add(context)
        }
    }

    @Component
    @Transactional
    class ProxiedStableHandler {
        @OutboxHandler(id = "proxied-stable")
        fun handle(
            payload: ProxiedEvent,
            metadata: OutboxRecordMetadata,
        ) = record("proxied-stable", payload.value, metadata.handlerId)
    }

    @Configuration
    class RetryPolicyConfiguration {
        @Bean("aliasRetryPolicy")
        fun aliasRetryPolicy(): OutboxRetryPolicy =
            OutboxRetryPolicy
                .builder()
                .maxRetries(maxRetries = 1)
                .fixedBackOff(delay = Duration.ofMillis(1))
                .build()
    }

    @SpringBootApplication
    class TestApplication

    data class Invocation(
        val handler: String,
        val value: String,
        val handlerId: String,
    )

    companion object {
        private val invocations = CopyOnWriteArrayList<Invocation>()
        private val fallbackContexts = CopyOnWriteArrayList<OutboxFailureContext>()

        private fun record(
            handler: String,
            value: String,
            handlerId: String = handler,
        ) {
            invocations.add(Invocation(handler, value, handlerId))
        }
    }
}
