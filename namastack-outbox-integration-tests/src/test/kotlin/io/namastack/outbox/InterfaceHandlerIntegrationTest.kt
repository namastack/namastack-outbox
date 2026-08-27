package io.namastack.outbox

import io.namastack.outbox.handler.OutboxHandler
import io.namastack.outbox.handler.OutboxHandlerIdentity
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.OutboxTypedHandler
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit.SECONDS

/** Verifies interface handler discovery, payload filtering, scheduling, and invocation end to end. */
@OutboxIntegrationTest
@Import(
    InterfaceHandlerIntegrationTest.TypedInterfaceHandler::class,
    InterfaceHandlerIntegrationTest.GenericInterfaceHandler::class,
    InterfaceHandlerIntegrationTest.SelectiveGenericInterfaceHandler::class,
    InterfaceHandlerIntegrationTest.SharedTypedInterfaceHandler::class,
)
class InterfaceHandlerIntegrationTest {
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
        invocations.clear()
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
    fun `typed interface handler processes matching payload`() {
        outbox.schedule(TypedEvent("typed"), "typed-key")

        await().atMost(10, SECONDS).untilAsserted {
            assertThat(invocations["typed-interface"]).containsExactly("typed")
            assertThat(recordRepository.findCompletedRecords())
                .extracting<String> { it.handlerId }
                .containsExactly("typed-interface")
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `generic interface handler processes supported payload`() {
        outbox.schedule(GenericEvent("generic"), "generic-key")

        await().atMost(10, SECONDS).untilAsserted {
            assertThat(invocations["generic-interface"]).containsExactly("generic")
            assertThat(recordRepository.findCompletedRecords())
                .extracting<String> { it.handlerId }
                .containsExactly("generic-interface")
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `selective generic handler schedules supported payload`() {
        outbox.schedule(SupportedSelectiveEvent("supported"), "supported-key")

        await().atMost(10, SECONDS).untilAsserted {
            assertThat(invocations["selective-generic"]).containsExactly("supported")
            assertThat(recordRepository.findCompletedRecords())
                .extracting<String> { it.handlerId }
                .containsExactly("selective-generic")
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `selective generic handler does not schedule unsupported payload`() {
        outbox.schedule(UnsupportedSelectiveEvent("unsupported"), "unsupported-key")

        assertThat(invocations["selective-generic"]).isNull()
        assertThat(recordRepository.findPendingRecords()).isEmpty()
        assertThat(recordRepository.findCompletedRecords()).isEmpty()
        assertThat(recordRepository.findFailedRecords()).isEmpty()
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `typed and generic handlers each receive one record for the same payload`() {
        outbox.schedule(SharedEvent("shared"), "shared-key")

        await().atMost(10, SECONDS).untilAsserted {
            assertThat(invocations["shared-typed"]).containsExactly("shared")
            assertThat(invocations["generic-interface"]).containsExactly("shared")
            assertThat(recordRepository.findCompletedRecords())
                .extracting<String> { it.handlerId }
                .containsExactlyInAnyOrder("shared-typed", "generic-interface")
        }
    }

    data class TypedEvent(
        val value: String,
    )

    data class GenericEvent(
        val value: String,
    )

    data class SupportedSelectiveEvent(
        val value: String,
    )

    data class UnsupportedSelectiveEvent(
        val value: String,
    )

    data class SharedEvent(
        val value: String,
    )

    @Component
    class TypedInterfaceHandler : OutboxTypedHandler<TypedEvent> {
        override fun getTypedHandlerIdentity() = OutboxHandlerIdentity("typed-interface")

        override fun handle(
            payload: TypedEvent,
            metadata: OutboxRecordMetadata,
        ) = record("typed-interface", payload.value, metadata)
    }

    @Component
    class GenericInterfaceHandler : OutboxHandler {
        override fun getGenericHandlerIdentity() = OutboxHandlerIdentity("generic-interface")

        override fun supports(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) = payload is GenericEvent || payload is SharedEvent

        override fun handle(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) {
            val value =
                when (payload) {
                    is GenericEvent -> payload.value
                    is SharedEvent -> payload.value
                    else -> error("Unexpected payload: $payload")
                }
            record("generic-interface", value, metadata)
        }
    }

    @Component
    class SelectiveGenericInterfaceHandler : OutboxHandler {
        override fun getGenericHandlerIdentity() = OutboxHandlerIdentity("selective-generic")

        override fun supports(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) = payload is SupportedSelectiveEvent

        override fun handle(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) = record("selective-generic", (payload as SupportedSelectiveEvent).value, metadata)
    }

    @Component
    class SharedTypedInterfaceHandler : OutboxTypedHandler<SharedEvent> {
        override fun getTypedHandlerIdentity() = OutboxHandlerIdentity("shared-typed")

        override fun handle(
            payload: SharedEvent,
            metadata: OutboxRecordMetadata,
        ) = record("shared-typed", payload.value, metadata)
    }

    @SpringBootApplication
    class TestApplication

    companion object {
        private val invocations = ConcurrentHashMap<String, MutableList<String>>()

        private fun record(
            handlerId: String,
            value: String,
            metadata: OutboxRecordMetadata,
        ) {
            check(metadata.handlerId == handlerId)
            invocations.computeIfAbsent(handlerId) { mutableListOf() }.add(value)
        }
    }
}
