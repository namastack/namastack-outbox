package io.namastack.outbox

import io.namastack.outbox.annotation.OutboxHandler
import io.namastack.outbox.config.JdbcOutboxAutoConfiguration
import io.namastack.outbox.config.JdbcOutboxSchemaAutoConfiguration
import io.namastack.outbox.config.OutboxCoreInfrastructureAutoConfiguration
import io.namastack.outbox.config.OutboxCoreProcessingAutoConfiguration
import io.namastack.outbox.config.OutboxCoreSchedulingAutoConfiguration
import io.namastack.outbox.config.OutboxCoreThreadingAutoConfiguration
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.instance.OutboxInstanceRegistry
import io.namastack.outbox.instance.OutboxInstanceRepository
import io.namastack.outbox.partition.PartitionAssignmentRepository
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.util.TestPropertyValues
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.transaction.support.TransactionTemplate
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import javax.sql.DataSource

/** Separate Single Mode Spring contexts share one database, without sharing runtime caches or handler state. */
class ProcessingGroupRecoveryIntegrationTest {
    @Test
    fun `new application context resumes persisted grouped retries with opt-in disabled`() {
        withDatabase { databaseUrl ->
            val pendingBeforeRestart: List<OutboxRecord<*>>
            val previousInstanceId: String
            startWorker(databaseUrl, failBilling = true).use { original ->
                original.schedule("original", "order:42")
                pendingBeforeRestart = original.awaitBillingRetry()
                previousInstanceId = original.instanceId
            }

            startWorker(databaseUrl, groupsEnabled = false).use { restarted ->
                assertThat(restarted.instanceId).isNotEqualTo(previousInstanceId)
                restarted.awaitExclusiveOwnership()
                restarted.assertRetryStillPending(pendingBeforeRestart)

                restarted.makeRetryDue(pendingBeforeRestart.first().id)
                restarted.awaitCompletedCount(3)
                restarted.assertResumedBillingDeliveries(pendingBeforeRestart)
            }
        }
    }

    @Test
    fun `replacement instance claims stale partitions and resumes the stored group ordering`() {
        withDatabase { databaseUrl ->
            startWorker(databaseUrl, failBilling = true).use { original ->
                original.schedule("original", "order:42")
                val pendingBeforeTakeover = original.awaitBillingRetry()
                original.awaitExclusiveOwnership()

                // Stop background work without lifecycle cleanup: registration and partition ownership remain in the DB.
                original.suspendWithoutDeregistering()
                assertThat(original.instances.findById(original.instanceId)).isNotNull()
                assertThat(original.assignments.findByInstanceId(original.instanceId)).hasSize(256)

                startWorker(databaseUrl).use { replacement ->
                    replacement.awaitExclusiveOwnership()
                    assertThat(replacement.instances.findById(original.instanceId)).isNull()
                    replacement.assertRetryStillPending(pendingBeforeTakeover)

                    // A different key can complete while the recovered billing key waits for its retry.
                    replacement.schedule("independent", "order:99")
                    replacement.awaitCompletedCount(4)
                    assertThat(replacement.records.findPendingRecords().map { it.id })
                        .containsExactlyElementsOf(pendingBeforeTakeover.map { it.id })

                    replacement.makeRetryDue(pendingBeforeTakeover.first().id)
                    replacement.awaitCompletedCount(6)
                    replacement.assertResumedBillingDeliveries(pendingBeforeTakeover)

                    // Late cleanup of the old context must not release partitions now owned by its successor.
                    original.close()
                    assertThat(replacement.assignments.findByInstanceId(replacement.instanceId)).hasSize(256)
                }
            }
        }
    }

    private fun withDatabase(test: (String) -> Unit) {
        val databaseUrl = "jdbc:h2:mem:processing-group-recovery-${UUID.randomUUID()}"
        // Keep the database alive across context shutdown; closing this last connection removes it after the test.
        DriverManager.getConnection(databaseUrl, "sa", "").use { test(databaseUrl) }
    }

    private fun startWorker(
        databaseUrl: String,
        failBilling: Boolean = false,
        groupsEnabled: Boolean = true,
    ): Worker {
        val context = AnnotationConfigApplicationContext()
        try {
            TestPropertyValues
                .of(
                    "test.database-url=$databaseUrl",
                    "test.fail-billing=$failBilling",
                    "namastack.outbox.processing-groups.enabled=$groupsEnabled",
                    "namastack.outbox.polling.fixed.interval=50ms",
                    "namastack.outbox.processing.stop-on-first-failure=true",
                    "namastack.outbox.retry.policy=fixed",
                    "namastack.outbox.retry.fixed.delay=1h",
                    "namastack.outbox.retry.max-retries=2",
                    "namastack.outbox.instance.heartbeat-interval=100ms",
                    "namastack.outbox.instance.stale-instance-timeout=2s",
                    "namastack.outbox.instance.rebalance-interval=100ms",
                ).applyTo(context)
            context.register(WorkerConfiguration::class.java)
            context.refresh()
            return Worker(context)
        } catch (failure: Throwable) {
            context.close()
            throw failure
        }
    }

    private class Worker(
        private val context: AnnotationConfigApplicationContext,
    ) : AutoCloseable {
        val instanceId = context.getBean<OutboxInstanceRegistry>().getCurrentInstanceId()
        val records = context.getBean<OutboxRecordRepository>()
        val instances = context.getBean<OutboxInstanceRepository>()
        val assignments = context.getBean<PartitionAssignmentRepository>()
        private val handlers = context.getBean<Handlers>()

        fun schedule(
            event: String,
            key: String,
        ) {
            context.getBean<TransactionTemplate>().executeWithoutResult {
                context.getBean<Outbox>().schedule(Event(event), key, mapOf("traceId" to event))
            }
        }

        fun awaitBillingRetry(): List<OutboxRecord<*>> {
            await().atMost(10, SECONDS).untilAsserted {
                assertThat(records.findCompletedRecords().map { it.key }).containsExactly("~ns.pg1~audit:order:42")
                val pending = records.findPendingRecords()
                assertThat(pending).hasSize(2)
                assertThat(pending.map { it.failureCount }).containsExactly(1, 0)
                assertThat(pending.first().nextRetryAt).isAfter(Instant.now())
            }
            return records.findPendingRecords()
        }

        fun awaitExclusiveOwnership() {
            await().atMost(15, SECONDS).untilAsserted {
                assertThat(assignments.findByInstanceId(instanceId)).hasSize(256)
                assertThat(assignments.findAll()).hasSize(256)
            }
        }

        fun assertRetryStillPending(expected: List<OutboxRecord<*>>) {
            await().during(300, MILLISECONDS).atMost(5, SECONDS).untilAsserted {
                val pending = records.findPendingRecords()
                assertThat(pending.map { it.id }).containsExactlyElementsOf(expected.map { it.id })
                assertThat(pending.map { it.key }).containsOnly("~ns.pg1~billing:order:42")
                assertThat(pending.map { it.failureCount }).containsExactly(1, 0)
                assertThat(pending.map { it.nextRetryAt }).containsExactlyElementsOf(expected.map { it.nextRetryAt })
                assertThat(handlers.calls).isEmpty()
            }
        }

        fun makeRetryDue(recordId: String) {
            // After verifying persistence of the one-hour delay, make only its deadline due to avoid a real-time wait.
            val updated =
                context
                    .getBean<JdbcClient>()
                    .sql("UPDATE outbox_record SET next_retry_at = :due WHERE id = :id")
                    .param("due", Timestamp.from(Instant.now().minusSeconds(1)))
                    .param("id", recordId)
                    .update()
            assertThat(updated).isEqualTo(1)
        }

        fun awaitCompletedCount(count: Int) {
            await().atMost(10, SECONDS).untilAsserted {
                assertThat(records.findCompletedRecords()).hasSize(count)
            }
        }

        fun assertResumedBillingDeliveries(previouslyPending: List<OutboxRecord<*>>) {
            val resumed = handlers.calls.filter { it.metadata.key == "order:42" }
            assertThat(resumed.map { it.metadata.handlerId })
                .containsExactlyElementsOf(previouslyPending.map { it.handlerId })
            assertThat(resumed.map { it.metadata.attempt }).containsExactly(2, 1)
            assertThat(resumed.map { it.metadata.processingGroup }).containsOnly("billing")
            assertThat(resumed.map { it.event }).containsOnly("original")
            assertThat(resumed.map { it.metadata.context }).containsOnly(mapOf("traceId" to "original"))
            assertThat(records.findCompletedRecords().map { it.id }).containsAll(previouslyPending.map { it.id })
            assertThat(records.findPendingRecords()).isEmpty()
            assertThat(records.findFailedRecords()).isEmpty()
        }

        fun suspendWithoutDeregistering() {
            context.getBean<OutboxProcessingScheduler>().stop()
            context.getBean<ThreadPoolTaskScheduler>(OutboxProcessingScheduler.SCHEDULER_NAME).shutdown()
            context.getBean<ThreadPoolTaskScheduler>(OutboxInstanceRegistry.SCHEDULER_NAME).shutdown()
        }

        override fun close() = context.close()
    }

    data class Event(
        val name: String,
    )

    data class Call(
        val event: String,
        val metadata: OutboxRecordMetadata,
    )

    class Handlers(
        private val failBilling: Boolean,
    ) {
        val calls = CopyOnWriteArrayList<Call>()

        @OutboxHandler(id = "invoice", processingGroup = "billing")
        fun invoice(
            event: Event,
            metadata: OutboxRecordMetadata,
        ) = record(event, metadata)

        @OutboxHandler(id = "ledger", processingGroup = "billing")
        fun ledger(
            event: Event,
            metadata: OutboxRecordMetadata,
        ) = record(event, metadata)

        @OutboxHandler(id = "audit", processingGroup = "audit")
        fun audit(
            event: Event,
            metadata: OutboxRecordMetadata,
        ) = record(event, metadata)

        private fun record(
            event: Event,
            metadata: OutboxRecordMetadata,
        ) {
            calls.add(Call(event.name, metadata))
            check(!failBilling || metadata.processingGroup != "billing") { "Billing temporarily unavailable" }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ImportAutoConfiguration(
        TaskExecutionAutoConfiguration::class,
        TaskSchedulingAutoConfiguration::class,
        OutboxCoreInfrastructureAutoConfiguration::class,
        OutboxCoreThreadingAutoConfiguration::class,
        OutboxCoreProcessingAutoConfiguration::class,
        OutboxCoreSchedulingAutoConfiguration::class,
        JdbcOutboxAutoConfiguration::class,
        JdbcOutboxSchemaAutoConfiguration::class,
        OutboxJacksonAutoConfiguration::class,
    )
    class WorkerConfiguration {
        @Bean
        fun dataSource(
            @Value("\${test.database-url}") url: String,
        ): DataSource =
            JdbcDataSource().apply {
                setURL(url)
                user = "sa"
            }

        @Bean
        fun transactionManager(dataSource: DataSource) = JdbcTransactionManager(dataSource)

        @Bean
        fun transactions(transactionManager: JdbcTransactionManager) = TransactionTemplate(transactionManager)

        @Bean
        fun jdbcClient(dataSource: DataSource) = JdbcClient.create(dataSource)

        @Bean
        fun handlers(
            @Value("\${test.fail-billing}") failBilling: Boolean,
        ) = Handlers(failBilling)
    }
}
