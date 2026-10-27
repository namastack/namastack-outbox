package io.namastack.example.delivery

import io.github.josipmusa.idempotency.core.AcquireResult
import io.github.josipmusa.idempotency.core.IdempotencyContext
import io.github.josipmusa.idempotency.core.IdempotencyStore
import io.namastack.example.delivery.billing.InvoiceIssued
import io.namastack.example.delivery.orders.OrderService
import io.namastack.outbox.OutboxProcessingScheduler
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.handler.invoker.OutboxHandlerInvoker
import io.namastack.outbox.instrumentation.OutboxRecordProcessingOutcome
import io.namastack.outbox.processor.OutboxRecordProcessorChainInvoker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset.UTC
import java.util.UUID

@SpringBootTest(
    properties = [
        "demo.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:delivery-idempotency;DB_CLOSE_DELAY=-1",
        "namastack.outbox.retry.policy=fixed",
        "namastack.outbox.retry.fixed.delay=1h",
    ],
)
class DeliveryIdempotencyTest {
    @Autowired private lateinit var orders: OrderService

    @Autowired private lateinit var jdbc: JdbcClient

    @Autowired private lateinit var records: OutboxRecordRepository

    @Autowired private lateinit var handlers: OutboxHandlerInvoker

    @Autowired private lateinit var processor: OutboxRecordProcessorChainInvoker

    @Autowired private lateinit var scheduler: OutboxProcessingScheduler

    @Autowired private lateinit var store: IdempotencyStore

    @Autowired private lateinit var publisher: ApplicationEventPublisher

    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    @BeforeEach
    fun controlDeliveryAttempts() {
        scheduler.stop()
    }

    @Test
    fun `all four handlers skip repeated deliveries while every handler gets its own execution`() {
        val orderId = orders.placeOrder("Repeated delivery", 4990)
        val initialDeliveries = pendingForOrder(orderId)
        assertThat(initialDeliveries).hasSize(3)

        // Dispatch through Namastack and the Spring proxies without acknowledging the deliveries.
        // Reloading and dispatching again reproduces the gap after the business commit.
        initialDeliveries.forEach(handlers::dispatch)
        val deliveries = pendingForOrder(orderId)
        assertThat(deliveries).hasSize(4)
        deliveries.forEach(handlers::dispatch)
        pendingForOrder(orderId).forEach { delivery ->
            assertThat(processor.process(delivery)).isEqualTo(OutboxRecordProcessingOutcome.COMPLETED)
        }

        assertThat(countForOrder("invoice", orderId)).isEqualTo(1)
        assertThat(countForOrder("ledger_entry", orderId)).isEqualTo(1)
        assertThat(countForOrder("notification", orderId)).isEqualTo(2)
        assertThat(pendingForOrder(orderId)).isEmpty()
        val completed = records.findCompletedRecords().filter { it.key.endsWith("order:$orderId") }
        assertThat(completed).hasSize(4)
        completed.forEach { assertThat(idempotencyStates(it)).containsExactly("COMPLETE") }
    }

    @Test
    fun `different deliveries sharing a business key are not deduplicated`() {
        val orderId = UUID.randomUUID()
        TransactionTemplate(transactionManager).executeWithoutResult {
            publisher.publishEvent(InvoiceIssued(UUID.randomUUID(), orderId, 1990))
            publisher.publishEvent(InvoiceIssued(UUID.randomUUID(), orderId, 2990))
        }

        val deliveries = pendingForOrder(orderId)
        assertThat(deliveries).hasSize(2)
        assertThat(deliveries.map { it.key }).containsOnly("~ns.pg1~notifications:order:$orderId")
        assertThat(deliveries.map { it.id }).doesNotHaveDuplicates()

        deliveries.forEach { assertThat(processor.process(it)).isEqualTo(OutboxRecordProcessingOutcome.COMPLETED) }

        assertThat(countForOrder("notification", orderId)).isEqualTo(2)
        deliveries.forEach { assertThat(idempotencyStates(it)).containsExactly("COMPLETE") }
    }

    @Test
    fun `an active lease schedules a retry without running or acknowledging the handler`() {
        val orderId = orders.placeOrder("Already in flight", 4990)
        val delivery = pendingInvoice(orderId)
        val context = leaseContext(delivery)
        val acquired = store.tryAcquire(context) as AcquireResult.Acquired

        try {
            assertThat(processor.process(delivery)).isEqualTo(OutboxRecordProcessingOutcome.RETRY_SCHEDULED)
            assertThat(countForOrder("invoice", orderId)).isZero()
            assertThat(pendingInvoice(orderId).failureCount).isEqualTo(1)
            assertThat(idempotencyStates(delivery)).containsExactly("IN_PROGRESS")
        } finally {
            store.release(context.identity(), acquired.leaseId())
        }

        makeRetryDue(delivery.id)
        assertThat(processor.process(pendingInvoice(orderId))).isEqualTo(OutboxRecordProcessingOutcome.COMPLETED)
        assertThat(countForOrder("invoice", orderId)).isEqualTo(1)
    }

    @Test
    fun `a delivery can recover an expired lease left by a stopped worker`() {
        val orderId = orders.placeOrder("Abandoned lease", 4990)
        val delivery = pendingInvoice(orderId)
        assertThat(store.tryAcquire(leaseContext(delivery))).isInstanceOf(AcquireResult.Acquired::class.java)

        // The store persists UTC wall-clock fields in a timestamp without a time zone.
        // Expire the lease explicitly instead of waiting for wall-clock time in this test.
        jdbc
            .sql("UPDATE idempotency_records SET lease_expires_at = ? WHERE idempotency_key = ?")
            .params(LocalDateTime.now(UTC).minusSeconds(1), delivery.id)
            .update()

        assertThat(processor.process(delivery)).isEqualTo(OutboxRecordProcessingOutcome.COMPLETED)
        assertThat(countForOrder("invoice", orderId)).isEqualTo(1)
        assertThat(idempotencyStates(delivery)).containsExactly("COMPLETE")
    }

    @Test
    fun `failure to persist idempotency completion rolls back invoice and follow-up`() {
        val orderId = orders.placeOrder("Idempotency storage failure", 4990)
        val delivery = pendingInvoice(orderId)

        // A test-only database constraint rejects the real JDBC completion update.
        // IN_PROGRESS acquisition is still permitted, so the handler runs before storage fails.
        val recordId = UUID.fromString(delivery.id)
        jdbc
            .sql(
                "ALTER TABLE idempotency_records ADD CONSTRAINT reject_test_completion " +
                    "CHECK (idempotency_key <> '$recordId' OR status <> 'COMPLETE')",
            ).update()
        try {
            assertThat(processor.process(delivery)).isEqualTo(OutboxRecordProcessingOutcome.RETRY_SCHEDULED)
            assertThat(countForOrder("invoice", orderId)).isZero()
            assertThat(pendingForOrder(orderId).map { it.handlerId }).doesNotContain("notifications.invoice-issued")
            assertThat(idempotencyStates(delivery)).isEmpty()
            assertThat(pendingInvoice(orderId).failureCount).isEqualTo(1)
        } finally {
            jdbc.sql("ALTER TABLE idempotency_records DROP CONSTRAINT reject_test_completion").update()
        }

        makeRetryDue(delivery.id)
        assertThat(processor.process(pendingInvoice(orderId))).isEqualTo(OutboxRecordProcessingOutcome.COMPLETED)
        assertThat(countForOrder("invoice", orderId)).isEqualTo(1)
        assertThat(idempotencyStates(delivery)).containsExactly("COMPLETE")
    }

    private fun pendingForOrder(orderId: UUID): List<OutboxRecord<*>> =
        records.findPendingRecords().filter { it.key.endsWith("order:$orderId") }

    private fun pendingInvoice(orderId: UUID): OutboxRecord<*> =
        pendingForOrder(orderId).single { it.handlerId == "billing.issue-invoice" }

    private fun leaseContext(delivery: OutboxRecord<*>): IdempotencyContext =
        IdempotencyContext
            .builder(delivery.handlerId, delivery.id)
            .leaseDuration(Duration.ofMinutes(1))
            .waitTimeout(Duration.ZERO)
            .build()

    private fun idempotencyStates(delivery: OutboxRecord<*>): List<String> =
        jdbc
            .sql("SELECT status FROM idempotency_records WHERE scope = ? AND idempotency_key = ?")
            .params(delivery.handlerId, delivery.id)
            .query(String::class.java)
            .list()
            .filterNotNull()

    private fun countForOrder(
        table: String,
        orderId: UUID,
    ): Long =
        jdbc
            .sql("SELECT COUNT(*) FROM $table WHERE order_id = ?")
            .param(orderId.toString())
            .query(Long::class.java)
            .single()

    private fun makeRetryDue(recordId: String) {
        jdbc
            .sql("UPDATE outbox_record SET next_retry_at = DATEADD('SECOND', -1, CURRENT_TIMESTAMP) WHERE id = ?")
            .param(recordId)
            .update()
    }
}
