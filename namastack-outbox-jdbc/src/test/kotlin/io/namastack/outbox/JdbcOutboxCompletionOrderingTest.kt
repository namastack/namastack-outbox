package io.namastack.outbox

import io.micrometer.observation.ObservationRegistry
import io.mockk.every
import io.mockk.mockk
import io.namastack.outbox.handler.invoker.OutboxHandlerInvoker
import io.namastack.outbox.instance.OutboxInstanceRegistry
import io.namastack.outbox.partition.PartitionAssignmentCache
import io.namastack.outbox.partition.PartitionCoordinator
import io.namastack.outbox.processor.OutboxRecordProcessorChainInvoker
import io.namastack.outbox.processor.PrimaryOutboxRecordProcessor
import io.namastack.outbox.runtime.OutboxRuntimeSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.DefaultTransactionStatus
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset.UTC
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

/** Exercises the real scheduler, processor and JDBC transactions independently of completion timestamps. */
class JdbcOutboxCompletionOrderingTest {
    @ParameterizedTest
    @CsvSource("false, true", "true, true", "false, false", "true, false")
    fun `successor waits for predecessor commit and remains pending if commit fails`(
        deleteCompletedRecords: Boolean,
        commitSucceeds: Boolean,
    ) {
        val database =
            EmbeddedDatabaseBuilder()
                .generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2)
                .addScript("schema/h2/outbox-tables.sql")
                .build()
        val transactions = BlockingCommitTransactionManager(database, commitSucceeds)
        val processing = Executors.newSingleThreadExecutor()
        try {
            val clock = Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), UTC)
            val repository =
                JdbcOutboxPersistenceFactory
                    .create(database, transactions, JacksonOutboxPayloadSerializer(JsonMapper.builder().build()), clock)
                    .recordRepository
            val first = record("first", Clock.offset(clock, Duration.ofSeconds(-2)))
            val second = record("second", Clock.offset(clock, Duration.ofSeconds(-1)))
            repository.create(first)
            repository.create(second)

            val jdbc = JdbcClient.create(database)
            val handled = CopyOnWriteArrayList<String>()
            val predecessorCommitted = AtomicBoolean()
            val handlers = mockk<OutboxHandlerInvoker>(relaxed = true)
            every { handlers.dispatch(any()) } answers {
                val record = firstArg<OutboxRecord<*>>()
                if (record.id == second.id) {
                    val status =
                        jdbc
                            .sql("SELECT status FROM outbox_record WHERE id = :id")
                            .param("id", first.id)
                            .query(String::class.java)
                            .optional()
                    predecessorCommitted.set(
                        if (deleteCompletedRecords) status.isEmpty else status.orElse("") == "COMPLETED",
                    )
                }
                handled.add(record.id)
            }
            val instances = mockk<OutboxInstanceRegistry>()
            every { instances.getCurrentInstanceId() } returns "test-instance"
            val assignments = mockk<PartitionAssignmentCache>()
            every { assignments.getAssignedPartitionNumbers("test-instance") } returns setOf(first.partition)
            val coordinator =
                PartitionCoordinator(
                    instanceRegistry = instances,
                    partitionAssignmentRepository = mockk(),
                    partitionAssignmentCache = assignments,
                    clock = clock,
                    taskScheduler = mockk(),
                    rebalanceInterval = Duration.ofSeconds(1),
                    observationRegistry = { ObservationRegistry.NOOP },
                )
            val settings =
                OutboxRuntimeSettings(
                    processing =
                        OutboxRuntimeSettings.Processing(
                            stopOnFirstFailure = true,
                            deleteCompletedRecords = deleteCompletedRecords,
                        ),
                )
            val scheduler =
                OutboxProcessingScheduler(
                    trigger = mockk(relaxed = true),
                    taskScheduler = mockk(relaxed = true),
                    observationRegistry = { ObservationRegistry.NOOP },
                    recordRepository = repository,
                    recordProcessorChainInvoker =
                        OutboxRecordProcessorChainInvoker(
                            PrimaryOutboxRecordProcessor(handlers, repository, settings.processing, clock),
                        ),
                    partitionCoordinator = coordinator,
                    taskExecutor = SyncTaskExecutor(),
                    settings = settings,
                    clock = clock,
                ).also { it.start() }

            transactions.blockNextCommit.set(true)
            val result = processing.submit { scheduler.process() }
            assertThat(transactions.commitReached.await(10, SECONDS)).isTrue()
            assertThat(handled).containsExactly(first.id)
            assertThat(repository.countByStatus(OutboxRecordStatus.NEW)).isEqualTo(2)
            assertThat(result.isDone).isFalse()

            transactions.releaseCommit.countDown()
            result.get(10, SECONDS)
            scheduler.stop()

            if (commitSucceeds) {
                assertThat(handled).containsExactly(first.id, second.id)
                assertThat(predecessorCommitted).isTrue()
                assertThat(repository.countByStatus(OutboxRecordStatus.NEW)).isZero()
                assertThat(repository.countByStatus(OutboxRecordStatus.COMPLETED))
                    .isEqualTo(if (deleteCompletedRecords) 0 else 2)
            } else {
                assertThat(handled).containsExactly(first.id)
                assertThat(repository.countByStatus(OutboxRecordStatus.NEW)).isEqualTo(2)
                assertThat(repository.countByStatus(OutboxRecordStatus.COMPLETED)).isZero()
            }
        } finally {
            transactions.releaseCommit.countDown()
            processing.shutdownNow()
            processing.awaitTermination(10, SECONDS)
            database.shutdown()
        }
    }

    private fun record(
        payload: String,
        clock: Clock,
    ): OutboxRecord<String> =
        OutboxRecord
            .Builder<String>()
            .key("same-key")
            .payload(payload)
            .handlerId("handler")
            .build(clock)

    private class BlockingCommitTransactionManager(
        source: DataSource,
        private val commitSucceeds: Boolean,
    ) : DataSourceTransactionManager(source) {
        val blockNextCommit = AtomicBoolean()
        val commitReached = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)

        init {
            isRollbackOnCommitFailure = true
        }

        override fun doCommit(status: DefaultTransactionStatus) {
            if (blockNextCommit.compareAndSet(true, false)) {
                commitReached.countDown()
                check(releaseCommit.await(30, SECONDS)) { "Completion commit was not released" }
                if (!commitSucceeds) throw TransactionSystemException("Simulated completion commit failure")
            }
            super.doCommit(status)
        }
    }
}
