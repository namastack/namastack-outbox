package io.namastack.example.delivery

import io.namastack.example.delivery.orders.OrderService
import io.namastack.outbox.OutboxProcessingScheduler
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxRecordStatus
import io.namastack.outbox.instrumentation.OutboxRecordProcessingOutcome
import io.namastack.outbox.processor.OutboxRecordProcessorChainInvoker
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS

@SpringBootTest(
    properties = [
        "demo.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:delivery-failures;DB_CLOSE_DELAY=-1",
        "namastack.outbox.retry.policy=fixed",
        "namastack.outbox.retry.fixed.delay=1h",
    ],
)
@Import(TransactionFailureConfiguration::class)
class DeliveryTransactionFailureTest {
    @Autowired private lateinit var orders: OrderService

    @Autowired private lateinit var jdbc: JdbcClient

    @Autowired private lateinit var records: OutboxRecordRepository

    @Autowired private lateinit var processor: OutboxRecordProcessorChainInvoker

    @Autowired private lateinit var scheduler: OutboxProcessingScheduler

    @Autowired private lateinit var failures: DeliveryFailures

    private val worker = Executors.newSingleThreadExecutor()

    @BeforeEach
    fun controlProcessingAttempts() {
        // Use the production processor chain on a worker thread, but advance attempts explicitly
        // so each committed or rolled-back database state can be inspected without polling races.
        scheduler.stop()
        failures.reset()
    }

    @AfterEach
    fun releaseWorker() {
        failures.reset()
        worker.shutdownNow()
    }

    @ParameterizedTest
    @EnumSource(
        value = FailurePoint::class,
        names = ["HANDLER_RUNTIME", "HANDLER_CHECKED", "HANDLER_COMMIT"],
    )
    fun `handler failure rolls back invoice and follow-up before persisting retry`(point: FailurePoint) {
        val orderId = orders.placeOrder("Handler rollback: $point", 4990)
        val original = pendingInvoice(orderId)
        failures.orderId = orderId
        failures.point = point

        assertThat(processOnWorker(original)).isEqualTo(OutboxRecordProcessingOutcome.RETRY_SCHEDULED)

        assertNoInvoiceOrFollowUp(orderId)
        assertThat(idempotencyStates(original.id)).isEmpty()
        val retry = pendingInvoice(orderId)
        assertThat(retry.failureCount).isEqualTo(1)
        assertThat(retry.completedAt).isNull()
        assertThat(retry.nextRetryAt).isAfter(Instant.now().plusSeconds(3000))
        assertThat(retry.failureReason).contains("Simulated")

        failures.reset()
        makeRetryDue(retry.id)
        assertThat(processOnWorker(pendingInvoice(orderId))).isEqualTo(OutboxRecordProcessingOutcome.COMPLETED)
        assertThat(invoiceCount(orderId)).isEqualTo(1)
        assertThat(followUpCount(orderId)).isEqualTo(1)
        assertThat(idempotencyStates(original.id)).containsExactly("COMPLETE")
        assertThat(records.findCompletedRecords().single { it.id == original.id }.failureCount).isEqualTo(1)
    }

    @Test
    fun `producer commit failure rolls back the order and every scheduled delivery`() {
        val orderCount = rowCount("customer_order")
        val deliveryCount = rowCount("outbox_record")
        failures.failNextCommit()

        assertThatThrownBy { orders.placeOrder("Producer commit failure", 4990) }
            .isInstanceOf(TransactionSystemException::class.java)

        assertThat(rowCount("customer_order")).isEqualTo(orderCount)
        assertThat(rowCount("outbox_record")).isEqualTo(deliveryCount)
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
    }

    @Test
    fun `retry commit failure leaves the original delivery available for another attempt`() {
        val orderId = orders.placeOrder("Retry commit failure", 4990)
        failures.orderId = orderId
        failures.point = FailurePoint.RETRY_COMMIT

        assertThatThrownBy { processOnWorker(pendingInvoice(orderId)) }
            .isInstanceOf(TransactionSystemException::class.java)

        assertNoInvoiceOrFollowUp(orderId)
        val pending = pendingInvoice(orderId)
        assertThat(pending.failureCount).isZero()
        assertThat(pending.completedAt).isNull()

        failures.reset()
        assertThat(processOnWorker(pending)).isEqualTo(OutboxRecordProcessingOutcome.COMPLETED)
        assertThat(invoiceCount(orderId)).isEqualTo(1)
        assertThat(followUpCount(orderId)).isEqualTo(1)
    }

    @Test
    fun `completion commit failure redelivers without repeating business changes or follow-up`() {
        val orderId = orders.placeOrder("Completion commit failure", 4990)
        val original = pendingInvoice(orderId)
        failures.orderId = orderId
        failures.point = FailurePoint.COMPLETION_COMMIT

        assertThatThrownBy { processOnWorker(original) }
            .isInstanceOf(TransactionSystemException::class.java)

        // The handler committed, but its original delivery did not. No handler retry is consumed.
        assertThat(invoiceCount(orderId)).isEqualTo(1)
        assertThat(followUpCount(orderId)).isEqualTo(1)
        assertThat(original.status).isEqualTo(OutboxRecordStatus.NEW)
        assertThat(original.completedAt).isNull()
        val pending = pendingInvoice(orderId)
        assertThat(pending.failureCount).isZero()
        assertThat(pending.completedAt).isNull()
        assertThat(idempotencyStates(original.id)).containsExactly("COMPLETE")

        failures.reset()
        assertThat(processOnWorker(pending)).isEqualTo(OutboxRecordProcessingOutcome.COMPLETED)

        // The original delivery is acknowledged on retry, but the handler body is skipped.
        assertThat(invoiceCount(orderId)).isEqualTo(1)
        assertThat(followUpCount(orderId)).isEqualTo(1)
        assertThat(records.findCompletedRecords().single { it.id == original.id }.failureCount).isZero()
    }

    @Test
    fun `lost handler commit acknowledgement keeps idempotency protection for the retry`() {
        val orderId = orders.placeOrder("Handler commit acknowledgement lost", 4990)
        failures.orderId = orderId
        failures.point = FailurePoint.HANDLER_COMMIT_ACK_LOST

        assertThat(processOnWorker(pendingInvoice(orderId))).isEqualTo(OutboxRecordProcessingOutcome.RETRY_SCHEDULED)

        assertThat(invoiceCount(orderId)).isEqualTo(1)
        assertThat(followUpCount(orderId)).isEqualTo(1)
        val retry = pendingInvoice(orderId)
        assertThat(retry.failureCount).isEqualTo(1)
        assertThat(retry.failureReason).contains("lost commit acknowledgement")
        assertThat(idempotencyStates(retry.id)).containsExactly("COMPLETE")

        failures.reset()
        makeRetryDue(retry.id)
        assertThat(processOnWorker(pendingInvoice(orderId))).isEqualTo(OutboxRecordProcessingOutcome.COMPLETED)
        assertThat(invoiceCount(orderId)).isEqualTo(1)
        assertThat(followUpCount(orderId)).isEqualTo(1)
    }

    @Test
    fun `lost completion acknowledgement preserves the committed status without scheduling handler retry`() {
        val orderId = orders.placeOrder("Completion acknowledgement lost", 4990)
        val original = pendingInvoice(orderId)
        failures.orderId = orderId
        failures.point = FailurePoint.COMPLETION_COMMIT_ACK_LOST

        assertThatThrownBy { processOnWorker(original) }
            .isInstanceOf(TransactionSystemException::class.java)

        assertThat(invoiceCount(orderId)).isEqualTo(1)
        assertThat(followUpCount(orderId)).isEqualTo(1)
        val completed = records.findCompletedRecords().single { it.id == original.id }
        assertThat(completed.failureCount).isZero()
        assertThat(completed.completedAt).isNotNull()
        assertThat(records.findPendingRecords().map { it.id }).doesNotContain(original.id)
    }

    private fun processOnWorker(record: OutboxRecord<*>): OutboxRecordProcessingOutcome {
        val attempt =
            worker.submit<OutboxRecordProcessingOutcome> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
                try {
                    processor.process(record)
                } finally {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
                }
            }
        return try {
            attempt.get(10, SECONDS)
        } catch (exception: ExecutionException) {
            throw exception.cause ?: exception
        }
    }

    private fun pendingInvoice(orderId: UUID): OutboxRecord<*> =
        records.findPendingRecords().single {
            it.handlerId == "billing.issue-invoice" && it.key.endsWith("order:$orderId")
        }

    private fun assertNoInvoiceOrFollowUp(orderId: UUID) {
        assertThat(invoiceCount(orderId)).isZero()
        assertThat(followUpCount(orderId)).isZero()
    }

    private fun invoiceCount(orderId: UUID): Long =
        jdbc
            .sql("SELECT COUNT(*) FROM invoice WHERE order_id = ?")
            .param(orderId.toString())
            .query(Long::class.java)
            .single()

    private fun followUpCount(orderId: UUID): Long =
        jdbc
            .sql("SELECT COUNT(*) FROM outbox_record WHERE handler_id = ? AND record_key LIKE ?")
            .params("notifications.invoice-issued", "%order:$orderId")
            .query(Long::class.java)
            .single()

    private fun rowCount(table: String): Long = jdbc.sql("SELECT COUNT(*) FROM $table").query(Long::class.java).single()

    private fun idempotencyStates(recordId: String): List<String> =
        jdbc
            .sql("SELECT status FROM idempotency_records WHERE scope = ? AND idempotency_key = ?")
            .params("billing.issue-invoice", recordId)
            .query(String::class.java)
            .list()
            .filterNotNull()

    private fun makeRetryDue(recordId: String) {
        assertThat(
            jdbc
                .sql("UPDATE outbox_record SET next_retry_at = ? WHERE id = ?")
                .params(Timestamp.from(Instant.now().minusSeconds(1)), recordId)
                .update(),
        ).isEqualTo(1)
    }
}
