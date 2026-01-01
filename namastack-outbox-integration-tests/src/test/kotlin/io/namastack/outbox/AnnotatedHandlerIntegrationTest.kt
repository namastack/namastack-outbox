package io.namastack.outbox

import io.namastack.outbox.annotation.EnableOutbox
import io.namastack.outbox.annotation.OutboxHandler
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
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit.SECONDS

@OutboxIntegrationTest
@Import(
    AnnotatedHandlerIntegrationTest.SimpleClassHandler::class,
    AnnotatedHandlerIntegrationTest.GenericInterfaceHandler::class,
    AnnotatedHandlerIntegrationTest.NonGenericInterfaceHandler::class,
    AnnotatedHandlerIntegrationTest.AbstractClassHandler::class,
    AnnotatedHandlerIntegrationTest.MultiLevelInheritanceHandler::class,
    AnnotatedHandlerIntegrationTest.MultipleInterfacesHandler::class,
    AnnotatedHandlerIntegrationTest.OverloadedMethodsHandler::class,
    AnnotatedHandlerIntegrationTest.MetadataParameterHandler::class,
)
class AnnotatedHandlerIntegrationTest {
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
        cleanupTables()
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `test simple class with annotated handler`() {
        outbox.schedule(SimpleEvent("simple-test"), "key1")

        await()
            .atMost(10, SECONDS)
            .untilAsserted {
                assertThat(handledEvents["SimpleClassHandler"]).containsExactly("simple-test")
                assertThat(recordRepository.findFailedRecords()).hasSize(0)
            }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `test generic interface implementation`() {
        outbox.schedule(GenericEvent("generic-test"), "key2")

        await()
            .atMost(10, SECONDS)
            .untilAsserted {
                assertThat(handledEvents["GenericInterfaceHandler"]).containsExactly("generic-test")
                assertThat(recordRepository.findFailedRecords()).hasSize(0)
            }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `test non-generic interface implementation`() {
        outbox.schedule(NonGenericEvent("non-generic-test"), "key3")

        await()
            .atMost(10, SECONDS)
            .untilAsserted {
                assertThat(handledEvents["NonGenericInterfaceHandler"]).containsExactly("non-generic-test")
                assertThat(recordRepository.findFailedRecords()).hasSize(0)
            }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `test abstract class implementation`() {
        outbox.schedule(AbstractEvent("abstract-test"), "key4")

        await()
            .atMost(10, SECONDS)
            .untilAsserted {
                assertThat(handledEvents["AbstractClassHandler"]).containsExactly("abstract-test")
                assertThat(recordRepository.findFailedRecords()).hasSize(0)
            }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `test multi-level inheritance`() {
        outbox.schedule(MultiLevelEvent("multi-level-test"), "key5")

        await()
            .atMost(10, SECONDS)
            .untilAsserted {
                assertThat(handledEvents["MultiLevelInheritanceHandler"]).containsExactly("multi-level-test")
                assertThat(recordRepository.findFailedRecords()).hasSize(0)
            }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `test multiple interfaces implementation`() {
        outbox.schedule(MultiInterfaceEvent("multi-interface-test"), "key6")

        await()
            .atMost(10, SECONDS)
            .untilAsserted {
                assertThat(handledEvents["MultipleInterfacesHandler"]).containsExactly("multi-interface-test")
                assertThat(recordRepository.findFailedRecords()).hasSize(0)
            }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `test overloaded methods in same class`() {
        outbox.schedule(OverloadEvent1("overload-1"), "key7")
        outbox.schedule(OverloadEvent2("overload-2"), "key8")

        await()
            .atMost(10, SECONDS)
            .untilAsserted {
                assertThat(handledEvents["OverloadedMethodsHandler-1"]).containsExactly("overload-1")
                assertThat(handledEvents["OverloadedMethodsHandler-2"]).containsExactly("overload-2")
                assertThat(recordRepository.findFailedRecords()).hasSize(0)
            }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `test handler with metadata parameter`() {
        outbox.schedule(MetadataEvent("metadata-test"), "key9")

        await()
            .atMost(10, SECONDS)
            .untilAsserted {
                assertThat(handledEvents["MetadataParameterHandler"]).containsExactly("metadata-test")
                assertThat(handledEvents["MetadataParameterHandler-handlerId"]).isNotEmpty
                assertThat(recordRepository.findFailedRecords()).hasSize(0)
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

    interface BaseEvent

    interface SpecializedEvent : BaseEvent

    data class SimpleEvent(
        val value: String,
    ) : BaseEvent

    data class GenericEvent(
        val value: String,
    ) : BaseEvent

    data class NonGenericEvent(
        val value: String,
    ) : BaseEvent

    data class AbstractEvent(
        val value: String,
    ) : BaseEvent

    data class MultiLevelEvent(
        val value: String,
    ) : SpecializedEvent

    data class MultiInterfaceEvent(
        val value: String,
    ) : BaseEvent,
        SpecializedEvent

    data class OverloadEvent1(
        val value: String,
    ) : BaseEvent

    data class OverloadEvent2(
        val value: String,
    ) : BaseEvent

    data class MetadataEvent(
        val value: String,
    ) : BaseEvent

    // Variant 1: Simple class with @OutboxHandler annotation
    @Component
    class SimpleClassHandler {
        @Suppress("UNUSED_PARAMETER")
        @OutboxHandler
        fun handle(
            event: SimpleEvent,
            metadata: OutboxRecordMetadata,
        ) {
            recordEvent("SimpleClassHandler", event.value)
        }
    }

    // Variant 2: Generic interface with @OutboxHandler annotation on interface method
    interface GenericHandlerInterface<T : BaseEvent> {
        @Suppress("UNUSED_PARAMETER")
        @OutboxHandler
        fun handle(
            event: T,
            metadata: OutboxRecordMetadata,
        )
    }

    @Component
    class GenericInterfaceHandler : GenericHandlerInterface<GenericEvent> {
        override fun handle(
            event: GenericEvent,
            metadata: OutboxRecordMetadata,
        ) {
            recordEvent("GenericInterfaceHandler", event.value)
        }
    }

    // Variant 3: Non-generic interface with @OutboxHandler annotation
    interface NonGenericHandlerInterface {
        @OutboxHandler
        fun handle(
            event: NonGenericEvent,
            metadata: OutboxRecordMetadata,
        )
    }

    @Component
    class NonGenericInterfaceHandler : NonGenericHandlerInterface {
        override fun handle(
            event: NonGenericEvent,
            metadata: OutboxRecordMetadata,
        ) {
            recordEvent("NonGenericInterfaceHandler", event.value)
        }
    }

    // Variant 4: Abstract class with @OutboxHandler annotation
    abstract class AbstractHandlerBase {
        @OutboxHandler
        abstract fun handle(
            event: AbstractEvent,
            metadata: OutboxRecordMetadata,
        )
    }

    @Component
    class AbstractClassHandler : AbstractHandlerBase() {
        override fun handle(
            event: AbstractEvent,
            metadata: OutboxRecordMetadata,
        ) {
            recordEvent("AbstractClassHandler", event.value)
        }
    }

    // Variant 5: Multi-level inheritance (interface -> abstract class -> concrete class)
    interface MultiLevelHandlerInterface {
        @OutboxHandler
        fun handle(
            event: MultiLevelEvent,
            metadata: OutboxRecordMetadata,
        )
    }

    abstract class MultiLevelHandlerBase : MultiLevelHandlerInterface

    @Component
    class MultiLevelInheritanceHandler : MultiLevelHandlerBase() {
        override fun handle(
            event: MultiLevelEvent,
            metadata: OutboxRecordMetadata,
        ) {
            recordEvent("MultiLevelInheritanceHandler", event.value)
        }
    }

    // Variant 6: Class implementing multiple interfaces
    interface HandlerInterfaceA<T : BaseEvent> {
        @OutboxHandler
        fun handleFromA(
            event: T,
            metadata: OutboxRecordMetadata,
        )
    }

    interface HandlerInterfaceB {
        fun someOtherMethod()
    }

    @Component
    class MultipleInterfacesHandler :
        HandlerInterfaceA<MultiInterfaceEvent>,
        HandlerInterfaceB {
        override fun handleFromA(
            event: MultiInterfaceEvent,
            metadata: OutboxRecordMetadata,
        ) {
            recordEvent("MultipleInterfacesHandler", event.value)
        }

        override fun someOtherMethod() {
            // Not an outbox handler
        }
    }

    // Variant 7: Multiple @OutboxHandler methods in same class (overloading)
    @Component
    class OverloadedMethodsHandler {
        @Suppress("UNUSED_PARAMETER")
        @OutboxHandler
        fun handle(
            event: OverloadEvent1,
            metadata: OutboxRecordMetadata,
        ) {
            recordEvent("OverloadedMethodsHandler-1", event.value)
        }

        @Suppress("UNUSED_PARAMETER")
        @OutboxHandler
        fun handle(
            event: OverloadEvent2,
            metadata: OutboxRecordMetadata,
        ) {
            recordEvent("OverloadedMethodsHandler-2", event.value)
        }
    }

    // Variant 8: Handler with OutboxRecordMetadata parameter
    @Component
    class MetadataParameterHandler {
        @Suppress("UNUSED_PARAMETER")
        @OutboxHandler
        fun handle(
            event: Any,
            metadata: OutboxRecordMetadata,
        ) {
            when (event) {
                is MetadataEvent -> {
                    recordEvent("MetadataParameterHandler", event.value)
                    recordEvent("MetadataParameterHandler-handlerId", metadata.handlerId)
                }
            }
        }
    }

    @EnableOutbox
    @EnableScheduling
    @SpringBootApplication
    class TestApplication

    companion object {
        val handledEvents = ConcurrentHashMap<String, MutableList<String>>()

        fun recordEvent(
            handlerName: String,
            value: String,
        ) {
            handledEvents.computeIfAbsent(handlerName) { mutableListOf() }.add(value)
        }
    }
}
