package io.namastack.outbox

import io.namastack.outbox.annotation.OutboxHandler
import io.namastack.outbox.handler.OutboxHandlerIdentity
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.OutboxTypedHandler
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS

/** No opt-in property: declarations must preserve the previous shared ordering boundary. */
@OutboxIntegrationTest
@Import(
    ProcessingGroupsDisabledIntegrationTest.InvoiceHandler::class,
    ProcessingGroupsDisabledIntegrationTest.AuditHandler::class,
    ProcessingGroupsDisabledIntegrationTest.Probe::class,
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProcessingGroupsDisabledIntegrationTest {
    @Autowired
    private lateinit var outbox: Outbox

    @Autowired
    private lateinit var repository: OutboxRecordRepository

    @Autowired
    private lateinit var transactions: TransactionTemplate

    @Autowired
    private lateinit var probe: Probe

    @AfterEach
    fun releaseHandler() {
        probe.release.countDown()
    }

    @Test
    fun `different declared groups still serialize together when opt-in is absent`() {
        probe.holdOrder42 = true
        transactions.executeWithoutResult {
            outbox.schedule(Event("shared"), "order:42")
            outbox.schedule(Event("independent"), "order:99")
        }

        try {
            assertThat(probe.entered.await(10, SECONDS)).isTrue()
            await().atMost(10, SECONDS).untilAsserted {
                assertThat(repository.findCompletedRecords()).hasSize(2)
            }
            await().during(300, MILLISECONDS).atMost(5, SECONDS).untilAsserted {
                assertThat(probe.callsFor("order:42")).hasSize(1)
                assertThat(repository.findPendingRecords().map { it.key }).containsExactly("order:42", "order:42")
            }
        } finally {
            probe.release.countDown()
        }

        await().atMost(10, SECONDS).untilAsserted {
            assertThat(repository.findCompletedRecords()).hasSize(4)
        }
        assertThat(probe.callsFor("order:42").map { it.handlerId }).containsExactlyInAnyOrder("invoice", "audit")
        assertThat(probe.calls.map { it.processingGroup }).containsOnly("default")
        assertThat(repository.findCompletedRecords().map { it.key }).containsOnly("order:42", "order:99")
    }

    @Test
    fun `failure blocks every declared group sharing the original business key`() {
        transactions.executeWithoutResult {
            outbox.schedule(Event("failed", fail = true), "order:42")
        }
        await().atMost(10, SECONDS).untilAsserted {
            assertThat(repository.findFailedRecords()).hasSize(1)
        }
        val failedHandler = repository.findFailedRecords().single().handlerId

        transactions.executeWithoutResult {
            outbox.schedule(Event("later"), "order:42")
            outbox.schedule(Event("independent"), "order:99")
        }
        await().atMost(10, SECONDS).untilAsserted {
            assertThat(repository.findCompletedRecords()).hasSize(2)
        }
        await().during(500, MILLISECONDS).atMost(5, SECONDS).untilAsserted {
            assertThat(repository.findFailedRecords()).hasSize(1)
            assertThat(repository.findPendingRecords().map { it.key })
                .containsExactly("order:42", "order:42", "order:42")
            val attempts = probe.callsFor("order:42")
            assertThat(attempts.map { it.handlerId }).containsOnly(failedHandler)
            assertThat(attempts.map { it.attempt }).containsExactly(1, 2, 3)
            assertThat(attempts.map { it.processingGroup }).containsOnly("default")
        }
    }

    data class Event(
        val name: String,
        val fail: Boolean = false,
    )

    @Component
    class Probe {
        val calls = CopyOnWriteArrayList<OutboxRecordMetadata>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        @Volatile var holdOrder42 = false

        fun callsFor(key: String) = calls.filter { it.key == key }

        fun handle(
            event: Event,
            metadata: OutboxRecordMetadata,
        ) {
            calls.add(metadata)
            if (holdOrder42 && metadata.key == "order:42") {
                entered.countDown()
                check(release.await(20, SECONDS)) { "Test did not release handler" }
            }
            check(!event.fail) { "Delivery failed" }
        }
    }

    @Component
    class InvoiceHandler(
        private val probe: Probe,
    ) {
        @OutboxHandler(id = "invoice", processingGroup = "billing")
        fun handle(
            event: Event,
            metadata: OutboxRecordMetadata,
        ) = probe.handle(event, metadata)
    }

    @Component
    class AuditHandler(
        private val probe: Probe,
    ) : OutboxTypedHandler<Event> {
        override fun getTypedHandlerIdentity() = OutboxHandlerIdentity("audit")

        override fun getTypedHandlerProcessingGroup() = "audit"

        override fun handle(
            payload: Event,
            metadata: OutboxRecordMetadata,
        ) = probe.handle(payload, metadata)
    }

    @SpringBootApplication
    class TestApplication
}
