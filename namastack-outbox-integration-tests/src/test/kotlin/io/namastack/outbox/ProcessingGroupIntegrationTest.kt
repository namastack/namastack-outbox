package io.namastack.outbox

import io.namastack.outbox.annotation.OutboxFallbackHandler
import io.namastack.outbox.annotation.OutboxHandler
import io.namastack.outbox.handler.OutboxFailureContext
import io.namastack.outbox.handler.OutboxHandlerIdentity
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.OutboxTypedHandlerWithFallback
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Import
import org.springframework.stereotype.Component
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import io.namastack.outbox.handler.OutboxHandler as GenericOutboxHandler

/** Exercises grouped fan-out through the real JPA repository, scheduler, and Spring handler proxies. */
@OutboxIntegrationTest
@Import(
    ProcessingGroupIntegrationTest.InvoiceHandler::class,
    ProcessingGroupIntegrationTest.LedgerHandler::class,
    ProcessingGroupIntegrationTest.AuditHandler::class,
    ProcessingGroupIntegrationTest.Probe::class,
)
@TestPropertySource(
    properties = [
        "namastack.outbox.processing-groups.enabled=true",
        "namastack.outbox.processing.stop-on-first-failure=true",
        "namastack.outbox.processing.executor-core-pool-size=4",
    ],
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProcessingGroupIntegrationTest {
    @Autowired
    private lateinit var outbox: Outbox

    @Autowired
    private lateinit var repository: OutboxRecordRepository

    @Autowired
    private lateinit var transactions: TransactionTemplate

    @Autowired
    private lateinit var probe: Probe

    @Autowired
    private lateinit var scheduler: OutboxProcessingScheduler

    @AfterEach
    fun releaseBlockedHandler() {
        probe.release.countDown()
    }

    @Test
    fun `same group and key serialize across handlers while other groups and keys progress`() {
        probe.holdPrimary = true
        // A poll can capture its retry cutoff during this transaction and omit later keys.
        // Stop polling until all keys are committed: a blocked batch cannot poll the omitted keys.
        scheduler.stop()
        transactions.executeWithoutResult {
            outbox.schedule(Event("first"), "order:42", mapOf("traceId" to "trace-1"))
            outbox.schedule(Event("second"), "order:42", mapOf("traceId" to "trace-1"))
            outbox.schedule(Event("independent"), "order:99", mapOf("traceId" to "trace-1"))
        }
        scheduler.start()

        try {
            assertThat(probe.entered.await(10, SECONDS)).isTrue()
            await().atMost(10, SECONDS).untilAsserted {
                // Three audit deliveries plus both billing deliveries for the other business key.
                assertThat(repository.findCompletedRecords()).hasSize(5)
            }
            await().during(300, MILLISECONDS).atMost(5, SECONDS).untilAsserted {
                assertThat(probe.billingCallsFor("order:42")).hasSize(1)
                assertThat(repository.findPendingRecords()).hasSize(4)
            }
        } finally {
            probe.release.countDown()
        }

        await().atMost(10, SECONDS).untilAsserted {
            assertThat(repository.findCompletedRecords()).hasSize(9)
            assertThat(probe.calls).hasSize(9)
        }
        val completed = repository.findCompletedRecords()
        assertThat(completed.map { it.key }).containsOnly(
            "~ns.pg1~billing:order:42",
            "~ns.pg1~billing:order:99",
            "~ns.pg1~audit:order:42",
            "~ns.pg1~audit:order:99",
        )
        listOf("first", "second", "independent").forEach { event ->
            val deliveries = completed.filter { (it.payload as Event).name == event }
            assertThat(deliveries.map { it.handlerId }).containsExactlyInAnyOrder("invoice", "ledger", "audit")
            assertThat(deliveries.map { it.createdAt }.distinct()).hasSize(1)
        }
        val expectedOrder =
            completed
                .filter { it.key == "~ns.pg1~billing:order:42" }
                .sortedWith(compareBy({ it.createdAt }, { it.id }))
                .map { (it.payload as Event).name to it.handlerId }
        val actualOrder = probe.billingCallsFor("order:42").map { it.event to it.metadata.handlerId }
        assertThat(actualOrder).containsExactlyElementsOf(expectedOrder)
        probe.calls.forEach {
            assertThat(it.metadata.key).isIn("order:42", "order:99")
            assertThat(it.metadata.context).containsExactlyEntriesOf(mapOf("traceId" to "trace-1"))
        }
        val invoiceCalls = probe.calls.filter { it.metadata.handlerId == "invoice" }
        assertThat(invoiceCalls.map { it.transactionActive }).containsOnly(true)
    }

    @Test
    fun `permanent failure blocks only the affected group and key across polling cycles`() {
        transactions.executeWithoutResult {
            outbox.schedule(
                Event("failed", DeliveryBehavior.PERMANENT_FAILURE),
                "order:42",
            )
        }
        await().atMost(10, SECONDS).untilAsserted {
            assertThat(repository.findFailedRecords()).hasSize(1)
            assertThat(repository.findCompletedRecords()).hasSize(1)
        }
        val failed = repository.findFailedRecords().single()
        transactions.executeWithoutResult {
            outbox.schedule(Event("later"), "order:42")
            outbox.schedule(Event("independent"), "order:99")
        }

        await().atMost(10, SECONDS).untilAsserted {
            assertThat(repository.findCompletedRecords()).hasSize(5)
        }
        await().during(500, MILLISECONDS).atMost(5, SECONDS).untilAsserted {
            assertThat(repository.findFailedRecords().map { it.id }).containsExactly(failed.id)
            assertThat(repository.findPendingRecords()).hasSize(3).allSatisfy {
                assertThat(it.key).isEqualTo("~ns.pg1~billing:order:42")
            }
            val attempts = probe.billingCallsFor("order:42")
            assertThat(attempts.map { it.event }).containsOnly("failed")
            assertThat(attempts.map { it.metadata.handlerId }).containsOnly(failed.handlerId)
            assertThat(attempts.map { it.metadata.attempt }).containsExactly(1, 2, 3)
        }
        assertThat(probe.failures).singleElement().satisfies({
            assertThat(it.processingGroup).isEqualTo("billing")
            assertThat(it.recordKey).isEqualTo("order:42")
        })
    }

    @Test
    fun `successful fallback after retries releases siblings and subsequent events in the group`() {
        probe.holdFallback = true
        transactions.executeWithoutResult {
            outbox.schedule(Event("recover", DeliveryBehavior.RECOVER_WITH_FALLBACK), "order:42")
        }
        try {
            assertThat(probe.entered.await(10, SECONDS)).isTrue()
            transactions.executeWithoutResult { outbox.schedule(Event("later"), "order:42") }
        } finally {
            probe.release.countDown()
        }

        await().atMost(10, SECONDS).untilAsserted {
            assertThat(repository.findCompletedRecords()).hasSize(6)
            assertThat(repository.findPendingRecords()).isEmpty()
            assertThat(repository.findFailedRecords()).isEmpty()
            assertThat(probe.failures).hasSize(2)
        }
        assertThat(probe.failures.map { it.handlerId }).containsExactlyInAnyOrder("invoice", "ledger")
        probe.failures.forEach {
            assertThat(it.recordKey).isEqualTo("order:42")
            assertThat(it.processingGroup).isEqualTo("billing")
            assertThat(it.failureCount).isEqualTo(3)
            assertThat(it.retriesExhausted).isTrue()
        }
        val billingCalls = probe.billingCallsFor("order:42")
        assertThat(billingCalls.map { it.event }).containsExactly(
            "recover",
            "recover",
            "recover", // First billing handler: initial attempt and two retries.
            "recover",
            "recover",
            "recover", // Second billing handler follows after successful fallback.
            "later",
            "later",
        )
        listOf("invoice", "ledger").forEach { handler ->
            val recoveryAttempts = billingCalls.filter { it.event == "recover" && it.metadata.handlerId == handler }
            assertThat(recoveryAttempts.map { it.metadata.attempt }).containsExactly(1, 2, 3)
        }
    }

    @Test
    fun `producer rollback removes the entire grouped fan-out`() {
        transactions.executeWithoutResult { transaction ->
            outbox.schedule(Event("rolled-back"), "order:42")
            assertThat(repository.findPendingRecords()).hasSize(3)
            transaction.setRollbackOnly()
        }

        await().during(300, MILLISECONDS).atMost(5, SECONDS).untilAsserted {
            assertThat(repository.findPendingRecords()).isEmpty()
            assertThat(repository.findCompletedRecords()).isEmpty()
            assertThat(repository.findFailedRecords()).isEmpty()
            assertThat(probe.calls).isEmpty()
        }
    }

    enum class DeliveryBehavior {
        SUCCESS,
        PERMANENT_FAILURE,
        RECOVER_WITH_FALLBACK,
    }

    data class Event(
        val name: String,
        val behavior: DeliveryBehavior = DeliveryBehavior.SUCCESS,
    )

    data class Call(
        val event: String,
        val metadata: OutboxRecordMetadata,
        val transactionActive: Boolean,
    )

    @Component
    class Probe {
        val calls = CopyOnWriteArrayList<Call>()
        val failures = CopyOnWriteArrayList<OutboxFailureContext>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        @Volatile var holdPrimary = false

        @Volatile var holdFallback = false

        fun billingCallsFor(key: String): List<Call> =
            calls.filter { it.metadata.processingGroup == "billing" && it.metadata.key == key }

        fun handle(
            event: Event,
            metadata: OutboxRecordMetadata,
        ) {
            calls.add(Call(event.name, metadata, TransactionSynchronizationManager.isActualTransactionActive()))
            if (metadata.processingGroup != "billing") return
            if (holdPrimary && metadata.key == "order:42") hold()
            check(event.behavior == DeliveryBehavior.SUCCESS) { "Billing delivery failed" }
        }

        fun fallback(
            event: Event,
            context: OutboxFailureContext,
        ) {
            failures.add(context)
            if (holdFallback) hold()
            check(event.behavior == DeliveryBehavior.RECOVER_WITH_FALLBACK) { "Billing fallback failed" }
        }

        private fun hold() {
            entered.countDown()
            check(release.await(20, SECONDS)) { "Test did not release handler" }
        }
    }

    @Component
    @Transactional
    class InvoiceHandler(
        private val probe: Probe,
    ) {
        @OutboxHandler(id = "invoice", processingGroup = "billing")
        fun handle(
            event: Event,
            metadata: OutboxRecordMetadata,
        ) = probe.handle(event, metadata)

        @OutboxFallbackHandler
        fun fallback(
            event: Event,
            context: OutboxFailureContext,
        ) = probe.fallback(event, context)
    }

    @Component
    class LedgerHandler(
        private val probe: Probe,
    ) : OutboxTypedHandlerWithFallback<Event> {
        override fun getTypedHandlerIdentity() = OutboxHandlerIdentity("ledger")

        override fun getTypedHandlerProcessingGroup() = "billing"

        override fun handle(
            payload: Event,
            metadata: OutboxRecordMetadata,
        ) = probe.handle(payload, metadata)

        override fun handleFailure(
            payload: Event,
            context: OutboxFailureContext,
        ) = probe.fallback(payload, context)
    }

    @Component
    class AuditHandler(
        private val probe: Probe,
    ) : GenericOutboxHandler {
        override fun getGenericHandlerIdentity() = OutboxHandlerIdentity("audit")

        override fun getGenericHandlerProcessingGroup() = "audit"

        override fun supports(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) = payload is Event && metadata.processingGroup == "audit"

        override fun handle(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) = probe.handle(payload as Event, metadata)
    }

    @SpringBootApplication
    class TestApplication
}
