package io.namastack.outbox

import io.namastack.outbox.annotation.OutboxEvent
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.OutboxTypedHandler
import io.namastack.outbox.mapping.EventMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger

/**
 * Integration test for @OutboxEvent annotation with Spring ApplicationEventPublisher.
 *
 * Verifies that events annotated with @OutboxEvent are automatically saved to outbox
 * and processed by registered handlers.
 */
@OutboxIntegrationTest
@Import(
    EventMulticasterIntegrationTest.EventPublisherService::class,
    EventMulticasterIntegrationTest.OrderEventHandler::class,
    EventMulticasterIntegrationTest.PaymentEventHandler::class,
    EventMulticasterIntegrationTest.MappingConfiguration::class,
    EventMulticasterIntegrationTest.NativeEventHandler::class,
)
class EventMulticasterIntegrationTest {
    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    private lateinit var jdbcClient: JdbcClient

    @Autowired
    private lateinit var recordRepository: OutboxRecordRepository

    @Autowired
    private lateinit var eventPublisherService: EventPublisherService

    @Autowired
    private lateinit var publisher: ApplicationEventPublisher

    @BeforeEach
    fun resetHandledEvents() {
        handledEvents.clear()
        mappingCalls.set(0)
        retryAttempts.set(0)
    }

    @AfterEach
    fun cleanup() {
        cleanupTables()
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `@OutboxEvent with static key saves to outbox and processes event`() {
        eventPublisherService.publishOrderCreated("order-123", "customer-456")

        await()
            .atMost(10, SECONDS)
            .untilAsserted {
                assertThat(recordRepository.findCompletedRecords()).hasSize(1)
                assertThat(handledEvents["OrderEventHandler"]).hasSize(1)

                val event = handledEvents["OrderEventHandler"]?.first() as OrderCreatedEvent
                assertThat(event.orderId).isEqualTo("order-123")
                assertThat(event.customerId).isEqualTo("customer-456")

                val record = recordRepository.findCompletedRecords().first()
                assertThat(record.key).isEqualTo("order")
            }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `@OutboxEvent with SpEL key expression resolves correctly`() {
        eventPublisherService.publishPaymentProcessed("payment-789", "order-123", 99.99)

        await()
            .atMost(10, SECONDS)
            .untilAsserted {
                assertThat(recordRepository.findCompletedRecords()).hasSize(1)
                assertThat(handledEvents["PaymentEventHandler"]).hasSize(1)

                val event = handledEvents["PaymentEventHandler"]?.first() as PaymentProcessedEvent
                assertThat(event.paymentId).isEqualTo("payment-789")
                assertThat(event.orderId).isEqualTo("order-123")
                assertThat(event.amount).isEqualTo(99.99)

                val record = recordRepository.findCompletedRecords().first()
                assertThat(record.key).isEqualTo(event.orderId)
            }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `multiple @OutboxEvent publications are all saved and processed`() {
        eventPublisherService.publishMultipleEvents()

        await()
            .atMost(10, SECONDS)
            .untilAsserted {
                assertThat(recordRepository.findCompletedRecords()).hasSize(3)
                assertThat(handledEvents["OrderEventHandler"]).hasSize(2)
                assertThat(handledEvents["PaymentEventHandler"]).hasSize(1)
            }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `mapped domain event persists and delivers only its integration payload`() {
        transactionTemplate.executeWithoutResult {
            publisher.publishEvent(InternalOrderPlaced("mapped-order", "customer-1"))
        }

        await().atMost(10, SECONDS).untilAsserted {
            val records = recordRepository.findCompletedRecords()
            assertThat(records).hasSize(1)
            assertThat(records.single().payload).isEqualTo(OrderCreatedEvent("mapped-order", "customer-1"))
            assertThat(
                handledEvents["OrderEventHandler"],
            ).containsExactly(OrderCreatedEvent("mapped-order", "customer-1"))
            assertThat(
                jdbcClient.sql("select count(*) from outbox_record").query(Long::class.java).single(),
            ).isEqualTo(1)
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `native Spring events map serialize persist and deserialize for their outbox handler`() {
        transactionTemplate.executeWithoutResult {
            publisher.publishEvent(NativeOrderPlaced("native-order"))
        }
        await().atMost(10, SECONDS).untilAsserted {
            val records = recordRepository.findCompletedRecords()
            assertThat(records).hasSize(1)
            assertThat(records.single().payload).isEqualTo(NativeOrderMessage("native-order"))
            assertThat(records.single().key).isEqualTo("native-order")
            assertThat(handledEvents["NativeEventHandler"]).containsExactly(NativeOrderMessage("native-order"))
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `retry reuses the stored integration payload without remapping the source`() {
        transactionTemplate.executeWithoutResult {
            publisher.publishEvent(InternalOrderPlaced("retry-order", "customer-1"))
        }

        await().atMost(10, SECONDS).untilAsserted {
            val records = recordRepository.findCompletedRecords()
            assertThat(records).hasSize(1)
            assertThat(records.single().payload).isEqualTo(OrderCreatedEvent("retry-order", "customer-1"))
            assertThat(
                handledEvents["OrderEventHandler"],
            ).containsExactly(OrderCreatedEvent("retry-order", "customer-1"))
            assertThat(retryAttempts.get()).isEqualTo(2)
            assertThat(mappingCalls.get()).isEqualTo(1)
            assertThat(
                jdbcClient.sql("select count(*) from outbox_record").query(Long::class.java).single(),
            ).isEqualTo(1)
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `failure of an original local listener rolls back the mapped outbox record and business data`() {
        jdbcClient.sql("create table if not exists mapping_business (id varchar(64) primary key)").update()
        assertThatThrownBy {
            transactionTemplate.executeWithoutResult {
                jdbcClient.sql("insert into mapping_business (id) values ('local-listener-failure')").update()
                publisher.publishEvent(InternalOrderPlaced("local-listener-failure", "customer-1"))
            }
        }.hasMessage("Original listener failed")

        assertThat(jdbcClient.sql("select count(*) from mapping_business").query(Long::class.java).single()).isZero()
        assertThat(jdbcClient.sql("select count(*) from outbox_record").query(Long::class.java).single()).isZero()
        assertThat(mappingCalls.get()).isEqualTo(1)
        assertThat(handledEvents).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `mapping and subsequent business failures roll back business data and outbox together`(failMapping: Boolean) {
        jdbcClient.sql("create table if not exists mapping_business (id varchar(64) primary key)").update()

        assertThatThrownBy {
            transactionTemplate.executeWithoutResult {
                jdbcClient.sql("insert into mapping_business (id) values ('rollback-order')").update()
                publisher.publishEvent(InternalOrderPlaced("rollback-order", "customer-1", failMapping))
                error("Business failed")
            }
        }.hasMessage(if (failMapping) "Mapping failed" else "Business failed")

        assertThat(jdbcClient.sql("select count(*) from mapping_business").query(Long::class.java).single()).isZero()
        assertThat(jdbcClient.sql("select count(*) from outbox_record").query(Long::class.java).single()).isZero()
        assertThat(handledEvents).isEmpty()
    }

    abstract class InternalOrderEvent {
        abstract val orderId: String
        abstract val customerId: String
        abstract val failMapping: Boolean
    }

    data class InternalOrderPlaced(
        override val orderId: String,
        override val customerId: String,
        override val failMapping: Boolean = false,
    ) : InternalOrderEvent()

    data class NativeOrderPlaced(
        val orderId: String,
    ) : ApplicationEvent("order-module")

    @OutboxEvent(key = "#this.orderId")
    data class NativeOrderMessage(
        val orderId: String,
    ) : ApplicationEvent("order-module")

    @Configuration(proxyBeanMethods = false)
    class MappingConfiguration {
        @Bean
        fun nativeMapper(): EventMapper<NativeOrderPlaced, NativeOrderMessage> =
            object : EventMapper<NativeOrderPlaced, NativeOrderMessage> {
                override fun map(event: NativeOrderPlaced): NativeOrderMessage = NativeOrderMessage(event.orderId)
            }

        @Bean
        fun orderMapper(): EventMapper<InternalOrderEvent, OrderCreatedEvent> =
            object : EventMapper<InternalOrderEvent, OrderCreatedEvent> {
                override fun map(event: InternalOrderEvent): OrderCreatedEvent {
                    mappingCalls.incrementAndGet()
                    check(!event.failMapping) { "Mapping failed" }
                    return OrderCreatedEvent(event.orderId, event.customerId)
                }
            }

        @EventListener
        fun originalEvent(event: InternalOrderEvent) {
            check(event.orderId != "local-listener-failure") { "Original listener failed" }
        }
    }

    private fun cleanupTables() {
        transactionTemplate.executeWithoutResult {
            jdbcClient.sql("DELETE FROM outbox_record").update()
            jdbcClient.sql("DELETE FROM outbox_instance").update()
            jdbcClient.sql("DELETE FROM outbox_partition").update()
        }
    }

    @OutboxEvent(key = "'order'")
    data class OrderCreatedEvent(
        val orderId: String,
        val customerId: String,
    )

    @OutboxEvent(key = "#root.orderId")
    data class PaymentProcessedEvent(
        val paymentId: String,
        val orderId: String,
        val amount: Double,
    )

    // Service that publishes events
    @Service
    class EventPublisherService(
        private val eventPublisher: ApplicationEventPublisher,
    ) {
        @Transactional
        fun publishOrderCreated(
            orderId: String,
            customerId: String,
        ) {
            eventPublisher.publishEvent(OrderCreatedEvent(orderId, customerId))
        }

        @Transactional
        fun publishPaymentProcessed(
            paymentId: String,
            orderId: String,
            amount: Double,
        ) {
            eventPublisher.publishEvent(PaymentProcessedEvent(paymentId, orderId, amount))
        }

        @Transactional
        fun publishMultipleEvents() {
            eventPublisher.publishEvent(OrderCreatedEvent("order-1", "customer-1"))
            eventPublisher.publishEvent(OrderCreatedEvent("order-2", "customer-2"))
            eventPublisher.publishEvent(PaymentProcessedEvent("payment-1", "order-1", 50.0))
        }
    }

    // Event Handlers
    @Component
    class OrderEventHandler : OutboxTypedHandler<OrderCreatedEvent> {
        override fun handle(
            payload: OrderCreatedEvent,
            metadata: OutboxRecordMetadata,
        ) {
            if (payload.orderId == "retry-order" && retryAttempts.incrementAndGet() == 1) {
                error("Transient handler failure")
            }
            handledEvents.computeIfAbsent("OrderEventHandler") { mutableListOf() }.add(payload)
        }
    }

    @Component
    class PaymentEventHandler : OutboxTypedHandler<PaymentProcessedEvent> {
        override fun handle(
            payload: PaymentProcessedEvent,
            metadata: OutboxRecordMetadata,
        ) {
            handledEvents.computeIfAbsent("PaymentEventHandler") { mutableListOf() }.add(payload)
        }
    }

    class NativeEventHandler : OutboxTypedHandler<NativeOrderMessage> {
        override fun handle(
            payload: NativeOrderMessage,
            metadata: OutboxRecordMetadata,
        ) {
            handledEvents.computeIfAbsent("NativeEventHandler") { mutableListOf() }.add(payload)
        }
    }

    companion object {
        val handledEvents = ConcurrentHashMap<String, MutableList<Any>>()
        val mappingCalls = AtomicInteger()
        val retryAttempts = AtomicInteger()
    }

    @SpringBootApplication
    class TestApplication
}
