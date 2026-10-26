package io.namastack.outbox

import io.namastack.outbox.annotation.OutboxHandler
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.partition.PartitionHasher
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
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS

/** Old plain keys and new default-group deliveries must continue to share one ordering boundary. */
@OutboxIntegrationTest
@Import(DefaultProcessingGroupIntegrationTest.Handlers::class)
@TestPropertySource(properties = ["namastack.outbox.processing-groups.enabled=true"])
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DefaultProcessingGroupIntegrationTest {
    @Autowired
    private lateinit var outbox: Outbox

    @Autowired
    private lateinit var repository: OutboxRecordRepository

    @Autowired
    private lateinit var transactions: TransactionTemplate

    @Autowired
    private lateinit var clock: Clock

    @Autowired
    private lateinit var handlers: Handlers

    @Test
    fun `old plain record precedes new default-group delivery after enabling groups`() {
        transactions.executeWithoutResult {
            saveLegacyRecord(OutboxRecordStatus.NEW)
            outbox.schedule(Event("new"), "order:42")
        }

        await().atMost(10, SECONDS).untilAsserted {
            assertThat(repository.findCompletedRecords()).hasSize(3)
        }
        val defaultCalls = handlers.calls.filter { it.metadata.processingGroup == "default" }
        assertThat(defaultCalls.map { it.event }).containsExactly("legacy", "new")
        assertThat(defaultCalls.map { it.metadata.handlerId }).containsExactly("default-v1", "default-v2")
        assertThat(handlers.calls.map { it.metadata.key }).containsOnly("order:42")
        assertThat(repository.findCompletedRecords().map { it.key })
            .containsExactlyInAnyOrder("order:42", "order:42", "~ns.pg1~billing:order:42")
    }

    @Test
    fun `failed legacy record blocks new default deliveries while a named group progresses`() {
        transactions.executeWithoutResult {
            saveLegacyRecord(OutboxRecordStatus.FAILED)
            outbox.schedule(Event("new"), "order:42")
        }

        await().atMost(10, SECONDS).untilAsserted {
            assertThat(repository.findCompletedRecords().map { it.key })
                .containsExactly("~ns.pg1~billing:order:42")
        }
        await().during(500, MILLISECONDS).atMost(5, SECONDS).untilAsserted {
            assertThat(repository.findFailedRecords().map { it.key }).containsExactly("order:42")
            assertThat(repository.findPendingRecords().map { it.key }).containsExactly("order:42")
            assertThat(handlers.calls.map { it.metadata.processingGroup }).containsExactly("billing")
        }
    }

    private fun saveLegacyRecord(status: OutboxRecordStatus) {
        val createdAt = clock.instant().minusSeconds(60)
        repository.save(
            OutboxRecord.restore(
                id = UUID.randomUUID().toString(),
                recordKey = "order:42",
                payload = Event("legacy"),
                context = emptyMap(),
                partition = PartitionHasher.getPartitionForRecordKey("order:42"),
                createdAt = createdAt,
                status = status,
                completedAt = null,
                failureCount = if (status == OutboxRecordStatus.FAILED) 3 else 0,
                failureException = null,
                failureReason = null,
                nextRetryAt = createdAt,
                handlerId = "default-v1",
            ),
        )
    }

    data class Event(
        val name: String,
    )

    data class Call(
        val event: String,
        val metadata: OutboxRecordMetadata,
    )

    @Component
    class Handlers {
        val calls = CopyOnWriteArrayList<Call>()

        @OutboxHandler(id = "default-v2", aliases = ["default-v1"])
        fun handleDefault(
            event: Event,
            metadata: OutboxRecordMetadata,
        ) {
            calls.add(Call(event.name, metadata))
        }

        @OutboxHandler(id = "billing", processingGroup = "billing")
        fun handleBilling(
            event: Event,
            metadata: OutboxRecordMetadata,
        ) {
            calls.add(Call(event.name, metadata))
        }
    }

    @SpringBootApplication
    class TestApplication
}
