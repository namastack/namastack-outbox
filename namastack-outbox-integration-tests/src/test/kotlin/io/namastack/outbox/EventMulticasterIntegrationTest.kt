package io.namastack.outbox

import io.namastack.outbox.annotation.OutboxEvent
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.OutboxTypedHandler
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit.SECONDS

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
)
class EventMulticasterIntegrationTest {
    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var recordRepository: OutboxRecordRepository

    @Autowired
    private lateinit var eventPublisherService: EventPublisherService

    @AfterEach
    fun cleanup() {
        handledEvents.clear()
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

    private fun cleanupTables() {
        transactionTemplate.executeWithoutResult {
            entityManager.createQuery("DELETE FROM OutboxRecordEntity").executeUpdate()
            entityManager.createQuery("DELETE FROM OutboxInstanceEntity").executeUpdate()
            entityManager.createQuery("DELETE FROM OutboxPartitionAssignmentEntity ").executeUpdate()
            entityManager.flush()
            entityManager.clear()
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

    companion object {
        val handledEvents = ConcurrentHashMap<String, MutableList<Any>>()
    }

    @EnableScheduling
    @SpringBootApplication
    class TestApplication
}
