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
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hibernate.SessionFactory
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.TaskScheduler
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ContextConfiguration
import org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset.UTC
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS

@DataJpaTest(
    properties = [
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.show-sql=false",
    ],
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(JpaOutboxAutoConfiguration::class, OutboxJacksonAutoConfiguration::class)
@ContextConfiguration(classes = [JpaOutboxRecordRepositoryTest.TestApplication::class])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class AbstractJpaBulkRecordLoadingTest {
    private val now = Instant.parse("2026-01-01T12:00:00Z")
    private val clock = Clock.fixed(now, UTC)
    private val serializer = CountingSerializer()
    private lateinit var repository: OutboxRecordRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var entityManagerFactory: EntityManagerFactory

    @Autowired
    private lateinit var transactions: TransactionTemplate

    private val statistics get() = entityManagerFactory.unwrap(SessionFactory::class.java).statistics

    @BeforeEach
    fun setUp() {
        repository = JpaOutboxRecordRepository(entityManager, transactions, OutboxRecordEntityMapper(serializer), clock)
    }

    @Test
    fun `one query returns exact key pages in creation time then id order without managed entity loads`() {
        create("a3", "a", now.plusSeconds(1))
        create("b1", "b")
        create("a2", "a")
        create("a1", "a")
        flushAndClear()
        statistics.clear()

        val pages = repository.findIncompleteRecordPages(setOf("b", "a", "missing"))

        assertThat(statistics.prepareStatementCount).isEqualTo(1)
        assertThat(statistics.entityLoadCount).isZero()
        assertThat(pages.keys).containsExactlyInAnyOrder("a", "b", "missing")
        assertThat(pages.getValue("a").map { it.id }).containsExactly("a1", "a2", "a3")
        assertThat(pages.getValue("b").map { it.id }).containsExactly("b1")
        assertThat(pages.getValue("missing")).isEmpty()
        assertThat(statistics.prepareStatementCount).isEqualTo(1)
        assertThat(pages.getValue("a").map { it.id })
            .containsExactlyElementsOf(repository.findIncompleteRecordsByRecordKey("a").map { it.id })
    }

    @Test
    fun `bulk read retains future retries and excludes completed and failed rows`() {
        create("a1", "a")
        create("a2", "a", now.plusSeconds(1), retry = now.plusSeconds(3600))
        create("a3", "a", now.plusSeconds(2))
        create("completed", "a", status = OutboxRecordStatus.COMPLETED)
        create("failed", "a", status = OutboxRecordStatus.FAILED)
        flushAndClear()

        val page = repository.findIncompleteRecordPages(setOf("a")).getValue("a")

        assertThat(page.map { it.id }).containsExactly("a1", "a2", "a3")
        assertThat(page.nextRetryAt(1)).isEqualTo(now.plusSeconds(3600))
        assertThat(page.isRetryDue(1, clock)).isFalse()
    }

    @Test
    fun `metadata access does not decode and cached records retain mutations`() {
        create("a1", "a")
        flushAndClear()
        serializer.reset()

        val page = repository.findIncompleteRecordPages(setOf("a")).getValue("a")

        assertThat(page).hasSize(1)
        assertThat(page.recordId(0)).isEqualTo("a1")
        assertThat(page.nextRetryAt(0)).isEqualTo(now)
        assertThat(page.isRetryDue(0, Clock.fixed(now.minusNanos(1), UTC))).isFalse()
        assertThat(page.isRetryDue(0, clock)).isTrue()
        assertThat(serializer.deserializations).isZero()
        assertThat(serializer.serializations).isZero()

        val record = page[0]
        assertThat(record.payload).isEqualTo("payload-a1")
        assertThat(record.context).containsEntry("trace", "original")
        assertThat(serializer.deserializations).isEqualTo(2)
        (record.context as MutableMap<String, String>)["trace"] = "mutated"
        assertThat(page[0]).isSameAs(record)
        assertThat(page[0].context).containsEntry("trace", "mutated")
        assertThat(serializer.deserializations).isEqualTo(2)
    }

    @Test
    fun `future unavailable payload types leave metadata and other keys readable`() {
        create("bad", "a", retry = now.plusSeconds(60))
        create("good", "b")
        entityManager.flush()
        entityManager
            .createQuery("update OutboxRecordEntity o set o.recordType = 'missing.Payload' where o.id = 'bad'")
            .executeUpdate()
        entityManager.clear()
        serializer.reset()

        val pages = repository.findIncompleteRecordPages(setOf("a", "b"))

        assertThat(pages.getValue("a").recordId(0)).isEqualTo("bad")
        assertThat(pages.getValue("a").isRetryDue(0, clock)).isFalse()
        assertThat(serializer.deserializations).isZero()
        assertThat(pages.getValue("b")[0].id).isEqualTo("good")
        assertThatThrownBy { pages.getValue("a")[0] }
            .isInstanceOf(OutboxPayloadTypeNotFoundException::class.java)
    }

    @Test
    fun `a malformed later payload or context leaves the valid prefix readable`() {
        create("a1", "a")
        create("a2", "a", now.plusSeconds(1))
        create("b1", "b")
        create("b2", "b", now.plusSeconds(1))
        entityManager.flush()
        entityManager
            .createQuery(
                "update OutboxRecordEntity o set o.payload = 'not-json' where o.id = 'a2'",
            ).executeUpdate()
        entityManager
            .createQuery(
                "update OutboxRecordEntity o set o.context = 'not-json' where o.id = 'b2'",
            ).executeUpdate()
        entityManager.clear()

        val pages = repository.findIncompleteRecordPages(setOf("a", "b"))

        assertThat(pages.getValue("a")[0].payload).isEqualTo("payload-a1")
        assertThat(pages.getValue("b")[0].payload).isEqualTo("payload-b1")
        assertThatThrownBy { pages.getValue("a")[1] }.isInstanceOf(Exception::class.java)
        assertThatThrownBy { pages.getValue("b")[1] }.isInstanceOf(Exception::class.java)
    }

    @Test
    fun `processing group storage keys remain separate in bulk reads`() {
        val keys = listOf("order:42", "~ns.pg1~billing:order:42", "~ns.pg1~audit:order:42", "~ns.pg1~billing:订单:ä:😀")
        keys.forEachIndexed { index, key -> create("record-$index", key) }
        flushAndClear()

        val pages = repository.findIncompleteRecordPages(keys.toSet())

        keys.forEachIndexed { index, key ->
            assertThat(pages.getValue(key).map { it.id }).containsExactly("record-$index")
        }
    }

    @Test
    fun `snapshots stay stable when managed entities change and the next read sees progress`() {
        val first = create("a1", "a")
        flushAndClear()
        val managed = entityManager.find(OutboxRecordEntity::class.java, first.id)
        val page = repository.findIncompleteRecordPages(setOf("a")).getValue("a")

        repository.update(completed(first))
        create("a2", "a", now.plusSeconds(1))
        entityManager.flush()

        assertThat(entityManager.contains(managed)).isTrue()
        assertThat(managed.status).isEqualTo(OutboxRecordStatus.COMPLETED)
        assertThat(page.nextRetryAt(0)).isEqualTo(now)
        assertThat(page[0].status).isEqualTo(OutboxRecordStatus.NEW)
        assertThat(repository.findIncompleteRecordPages(setOf("a")).getValue("a").map { it.id })
            .containsExactly("a2")
    }

    @Test
    @Transactional(propagation = NOT_SUPPORTED)
    fun `pages fetched without a transaction decode on another thread without further database access`() {
        val workers = Executors.newSingleThreadExecutor()
        try {
            create("outside-transaction", "outside")
            statistics.clear()
            val page = repository.findIncompleteRecordPages(setOf("outside")).getValue("outside")
            val statementsAfterFetch = statistics.prepareStatementCount

            val record = workers.submit<OutboxRecord<*>> { page[0] }.get(5, SECONDS)

            assertThat(record.payload).isEqualTo("payload-outside-transaction")
            assertThat(record.context).containsEntry("trace", "original")
            assertThat(page[0]).isSameAs(record)
            assertThat(statistics.prepareStatementCount).isEqualTo(statementsAfterFetch)
            assertThat(statistics.entityLoadCount).isZero()
        } finally {
            workers.shutdownNow()
            transactions.executeWithoutResult {
                entityManager
                    .createQuery(
                        "delete from OutboxRecordEntity o where o.recordKey = 'outside'",
                    ).executeUpdate()
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    @Transactional(propagation = NOT_SUPPORTED)
    fun `real workers preserve strict and non-strict retry behavior and commit before successors`(strict: Boolean) {
        try {
            create("a1", "a", now.minusSeconds(3))
            create("a2", "a", now.minusSeconds(2), retry = now.plusSeconds(3600))
            create("a3", "a", now.minusSeconds(1))
            create("b1", "b")
            val handled = Collections.synchronizedList(mutableListOf<String>())

            runProcessingCycle(strict) { record ->
                if (record.id == "a3") {
                    assertThat(repository.findCompletedRecords().map { it.id }).contains("a1")
                }
                handled.add(record.id)
            }

            val expected = if (strict) listOf("a1", "b1") else listOf("a1", "a3", "b1")
            assertThat(handled).containsExactlyInAnyOrderElementsOf(expected)
            assertThat(repository.findCompletedRecords().map { it.id }).containsExactlyInAnyOrderElementsOf(expected)
            assertThat(repository.findIncompleteRecordsByRecordKey("a").map { it.id })
                .containsExactlyElementsOf(if (strict) listOf("a2", "a3") else listOf("a2"))
            assertThat(repository.findRecordKeysInPartitions(setOf(1), OutboxRecordStatus.NEW, 10, strict)).isEmpty()
        } finally {
            deleteAllRecords()
        }
    }

    @Test
    @Transactional(propagation = NOT_SUPPORTED)
    fun `a real completion persistence failure stops its key while another key completes in order`() {
        try {
            create("a1", "a", now.minusSeconds(2))
            create("a2", "a", now.minusSeconds(1))
            create("b1", "b", now.minusSeconds(2))
            create("b2", "b", now.minusSeconds(1))
            transactions.executeWithoutResult {
                entityManager
                    .createNativeQuery(
                        "ALTER TABLE outbox_record ADD CONSTRAINT reject_bulk_completion " +
                            "CHECK (id <> 'a1' OR status <> 'COMPLETED')",
                    ).executeUpdate()
            }
            val handled = Collections.synchronizedList(mutableListOf<String>())

            runProcessingCycle(strict = true) { record -> handled.add(record.id) }

            assertThat(handled).containsExactlyInAnyOrder("a1", "b1", "b2")
            assertThat(handled.filter { it.startsWith("b") }).containsExactly("b1", "b2")
            assertThat(repository.findCompletedRecords().map { it.id }).containsExactly("b1", "b2")
            val pending = repository.findIncompleteRecordsByRecordKey("a")
            assertThat(pending.map { it.id }).containsExactly("a1", "a2")
            assertThat(pending.first().failureCount).isZero()
        } finally {
            transactions.executeWithoutResult {
                entityManager
                    .createNativeQuery(
                        "ALTER TABLE outbox_record DROP CONSTRAINT IF EXISTS reject_bulk_completion",
                    ).executeUpdate()
            }
            deleteAllRecords()
        }
    }

    @Test
    fun `empty requests avoid queries and the portable key bound is enforced`() {
        statistics.clear()
        assertThat(repository.findIncompleteRecordPages(emptySet())).isEmpty()
        assertThat(statistics.prepareStatementCount).isZero()
        assertThat(repository.findIncompleteRecordPages((1..1000).map { "missing-$it" }.toSet())).hasSize(1000)
        assertThatThrownBy { repository.findIncompleteRecordPages((1..1001).map { "missing-$it" }.toSet()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun runProcessingCycle(
        strict: Boolean,
        handle: (OutboxRecord<*>) -> Unit,
    ) {
        val workers = Executors.newFixedThreadPool(2)
        val taskScheduler = mockk<TaskScheduler>(relaxed = true)
        val registry = mockk<OutboxInstanceRegistry>()
        val assignments = mockk<PartitionAssignmentCache>()
        val handlers = mockk<OutboxHandlerInvoker>(relaxed = true)
        every { registry.getCurrentInstanceId() } returns "test-instance"
        every { assignments.getAssignedPartitionNumbers("test-instance") } returns setOf(1)
        every { handlers.dispatch(any()) } answers { handle(firstArg()) }
        val settings =
            OutboxRuntimeSettings(
                processing =
                    OutboxRuntimeSettings.Processing(
                        stopOnFirstFailure = strict,
                        deleteCompletedRecords = false,
                    ),
            )
        val coordinator =
            PartitionCoordinator(
                instanceRegistry = registry,
                partitionAssignmentRepository = mockk(),
                partitionAssignmentCache = assignments,
                clock = clock,
                taskScheduler = taskScheduler,
                rebalanceInterval = Duration.ofSeconds(10),
                observationRegistry = { ObservationRegistry.NOOP },
            )
        val scheduler =
            OutboxProcessingScheduler(
                trigger = mockk(relaxed = true),
                taskScheduler = taskScheduler,
                observationRegistry = { ObservationRegistry.NOOP },
                recordRepository = repository,
                recordProcessorChainInvoker =
                    OutboxRecordProcessorChainInvoker(
                        PrimaryOutboxRecordProcessor(handlers, repository, settings.processing, clock),
                    ),
                partitionCoordinator = coordinator,
                taskExecutor = TaskExecutor { workers.execute(it) },
                settings = settings,
                clock = clock,
            )
        try {
            scheduler.start()
            scheduler.process()
        } finally {
            scheduler.stop()
            workers.shutdownNow()
        }
    }

    private fun deleteAllRecords() {
        transactions.executeWithoutResult {
            entityManager
                .createQuery(
                    "delete from OutboxRecordEntity",
                ).executeUpdate()
        }
    }

    private fun flushAndClear() {
        entityManager.flush()
        entityManager.clear()
    }

    private fun create(
        id: String,
        key: String,
        created: Instant = now,
        retry: Instant = now,
        status: OutboxRecordStatus = OutboxRecordStatus.NEW,
    ): OutboxRecord<String> =
        repository.create(
            OutboxRecord.restore(
                id = id,
                recordKey = key,
                payload = "payload-$id",
                context = mapOf("trace" to "original"),
                partition = 1,
                createdAt = created,
                status = status,
                completedAt = if (status == OutboxRecordStatus.COMPLETED) now else null,
                failureCount = 0,
                failureException = null,
                failureReason = null,
                nextRetryAt = retry,
                handlerId = "handler",
            ),
        )

    private fun completed(record: OutboxRecord<*>): OutboxRecord<*> =
        OutboxRecord.restore(
            id = record.id,
            recordKey = record.key,
            payload = record.payload,
            context = record.context,
            partition = record.partition,
            createdAt = record.createdAt,
            status = OutboxRecordStatus.COMPLETED,
            completedAt = now,
            failureCount = record.failureCount,
            failureReason = record.failureReason,
            nextRetryAt = now.plusSeconds(60),
            handlerId = record.handlerId,
            failureException = record.failureException,
        )

    private class CountingSerializer : OutboxPayloadSerializer {
        private val delegate = JacksonOutboxPayloadSerializer(JsonMapper.builder().build())
        var serializations = 0
        var deserializations = 0

        override fun serialize(payload: Any): String {
            serializations++
            return delegate.serialize(payload)
        }

        override fun <T> deserialize(
            serialized: String,
            type: Class<T>,
        ): T {
            deserializations++
            return delegate.deserialize(serialized, type)
        }

        fun reset() {
            serializations = 0
            deserializations = 0
        }
    }
}
