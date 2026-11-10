package io.namastack.outbox

import io.namastack.outbox.annotation.OutboxHandler
import io.namastack.outbox.handler.OutboxHandlerIdentity
import io.namastack.outbox.handler.OutboxRecordMetadata
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Import
import org.springframework.stereotype.Component
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import io.namastack.outbox.handler.OutboxHandler as GenericOutboxHandler

/** Different payload types share one persisted group/key boundary through real workers and JPA. */
@OutboxIntegrationTest
@Import(
    ProcessingGroupEventTypesIntegrationTest.BillingHandlers::class,
    ProcessingGroupEventTypesIntegrationTest.AuditHandler::class,
    ProcessingGroupEventTypesIntegrationTest.Probe::class,
)
@TestPropertySource(
    properties = [
        "namastack.outbox.processing-groups.enabled=true",
        "namastack.outbox.processing.stop-on-first-failure=true",
        "namastack.outbox.processing.executor-core-pool-size=4",
        "namastack.outbox.retry.fixed.delay=1h",
    ],
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProcessingGroupEventTypesIntegrationTest {
    @Autowired
    private lateinit var outbox: Outbox

    @Autowired
    private lateinit var records: OutboxRecordRepository

    @Autowired
    private lateinit var transactions: TransactionTemplate

    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var probe: Probe

    @Test
    fun `a retry blocks a different event type in the same group and key until recovery`() {
        val placed = OrderPlaced("42")
        val cancelled = OrderCancelled("42")
        val independent = OrderCancelled("99")

        transactions.executeWithoutResult { outbox.schedule(placed, "order:42") }
        await().atMost(10, SECONDS).untilAsserted {
            assertThat(pendingBillingDeliveries()).singleElement().satisfies({
                assertThat(it.payload).isEqualTo(placed)
                assertThat(it.failureCount).isEqualTo(1)
            })
        }
        val retryId = pendingBillingDeliveries().single().id

        transactions.executeWithoutResult {
            outbox.schedule(cancelled, "order:42")
            outbox.schedule(independent, "order:99")
        }

        await().atMost(10, SECONDS).untilAsserted {
            assertThat(probe.auditCalls.map { it.event }).containsExactlyInAnyOrder(placed, cancelled, independent)
            assertThat(probe.billingCallsFor("order:99").map { it.event }).containsExactly(independent)
            assertThat(records.findCompletedRecords()).hasSize(4)
        }
        await().during(300, MILLISECONDS).atMost(5, SECONDS).untilAsserted {
            assertThat(probe.billingCallsFor("order:42").map { it.event }).containsExactly(placed)
            assertThat(pendingBillingDeliveries().map { it.payload }).containsExactly(placed, cancelled)
        }

        probe.failOrder42 = false
        makeRetryDue(retryId)

        await().atMost(10, SECONDS).untilAsserted {
            assertThat(records.findCompletedRecords()).hasSize(6)
            assertThat(records.findPendingRecords()).isEmpty()
            assertThat(records.findFailedRecords()).isEmpty()
        }
        val billingCalls = probe.billingCallsFor("order:42")
        assertThat(billingCalls.map { it.event }).containsExactly(placed, placed, cancelled)
        assertThat(billingCalls.map { it.metadata.attempt }).containsExactly(1, 2, 1)
        assertThat(billingCalls.map { it.metadata.handlerId })
            .containsExactly("billing.order-placed", "billing.order-placed", "billing.order-cancelled")
        assertThat(billingCalls.map { it.metadata.processingGroup }).containsOnly("billing")
        assertThat(probe.auditCalls.map { it.metadata.processingGroup }).containsOnly("audit")
    }

    private fun pendingBillingDeliveries(): List<OutboxRecord<*>> =
        records.findPendingRecords().filter { it.processingGroup == "billing" && it.businessKey == "order:42" }

    private fun makeRetryDue(recordId: String) {
        transactions.executeWithoutResult {
            entityManager
                .createNativeQuery("UPDATE outbox_record SET next_retry_at = created_at WHERE id = :id")
                .setParameter("id", recordId)
                .executeUpdate()
        }
    }

    data class OrderPlaced(
        val orderId: String,
    )

    data class OrderCancelled(
        val orderId: String,
    )

    data class Call(
        val event: Any,
        val metadata: OutboxRecordMetadata,
    )

    @Component
    class Probe {
        val billingCalls = CopyOnWriteArrayList<Call>()
        val auditCalls = CopyOnWriteArrayList<Call>()

        @Volatile var failOrder42 = true

        fun billingCallsFor(key: String): List<Call> = billingCalls.filter { it.metadata.key == key }
    }

    @Component
    class BillingHandlers(
        private val probe: Probe,
    ) {
        @OutboxHandler(id = "billing.order-placed", processingGroup = "billing")
        fun orderPlaced(
            event: OrderPlaced,
            metadata: OutboxRecordMetadata,
        ) {
            probe.billingCalls += Call(event, metadata)
            check(!probe.failOrder42 || event.orderId != "42") { "OrderPlaced delivery failed" }
        }

        @OutboxHandler(id = "billing.order-cancelled", processingGroup = "billing")
        fun orderCancelled(
            event: OrderCancelled,
            metadata: OutboxRecordMetadata,
        ) {
            probe.billingCalls += Call(event, metadata)
        }
    }

    @Component
    class AuditHandler(
        private val probe: Probe,
    ) : GenericOutboxHandler {
        override fun getGenericHandlerIdentity() = OutboxHandlerIdentity("audit.order-events")

        override fun getGenericHandlerProcessingGroup() = "audit"

        override fun supports(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) = payload is OrderPlaced || payload is OrderCancelled

        override fun handle(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) {
            probe.auditCalls += Call(payload, metadata)
        }
    }

    @SpringBootApplication
    class TestApplication
}
