package io.namastack.outbox

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.event.CommandListener
import com.mongodb.event.CommandStartedEvent
import io.micrometer.observation.ObservationRegistry
import io.mockk.every
import io.mockk.mockk
import io.namastack.outbox.config.MongoOutboxConfigurationProperties
import io.namastack.outbox.handler.invoker.OutboxHandlerInvoker
import io.namastack.outbox.instance.OutboxInstanceRegistry
import io.namastack.outbox.partition.PartitionAssignmentCache
import io.namastack.outbox.partition.PartitionCoordinator
import io.namastack.outbox.processor.OutboxRecordProcessorChainInvoker
import io.namastack.outbox.processor.PrimaryOutboxRecordProcessor
import io.namastack.outbox.runtime.OutboxRuntimeSettings
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.bson.Document
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.core.task.TaskExecutor
import org.springframework.data.mongodb.MongoTransactionManager
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.scheduling.TaskScheduler
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mongodb.MongoDBContainer
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset.UTC
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger

@Testcontainers
class MongoBulkRecordLoadingIntegrationTest {
    private val now = Instant.parse("2026-01-01T12:00:00Z")
    private val clock = Clock.fixed(now, UTC)
    private val serializer = CountingSerializer()
    private val commands = ReadCommandCounter()
    private val collectionName = MongoOutboxRecordEntity.COLLECTION_NAME
    private lateinit var client: MongoClient
    private lateinit var mongoTemplate: MongoTemplate
    private lateinit var repository: MongoOutboxRecordRepository

    @BeforeEach
    fun setUp() {
        client =
            MongoClients.create(
                MongoClientSettings
                    .builder()
                    .applyConnectionString(ConnectionString(mongodb.connectionString))
                    .addCommandListener(commands)
                    .build(),
            )
        mongoTemplate = MongoTemplate(client, "bulk_loading_${UUID.randomUUID().toString().replace("-", "")}")
        repository = repository()
    }

    @AfterEach
    fun tearDown() {
        try {
            mongoTemplate.db.drop()
        } finally {
            client.close()
        }
    }

    @Test
    fun `one find returns exact key pages in creation time then id order`() {
        create("a3", "a", now.plusSeconds(1))
        create("b1", "b")
        create("a2", "a")
        create("a1", "a")

        val pages = repository.findIncompleteRecordPages(setOf("b", "a", "missing"))

        assertThat(commands.finds.get()).isEqualTo(1)
        assertThat(pages.keys).containsExactlyInAnyOrder("a", "b", "missing")
        assertThat(pages.getValue("a").map { it.id }).containsExactly("a1", "a2", "a3")
        assertThat(pages.getValue("b").map { it.id }).containsExactly("b1")
        assertThat(pages.getValue("missing")).isEmpty()
        assertThat(commands.finds.get()).isEqualTo(1)
        assertThat(pages.getValue("a").map { it.id })
            .containsExactlyElementsOf(repository.findIncompleteRecordsByRecordKey("a").map { it.id })
    }

    @Test
    fun `bulk read retains future retries and excludes completed and failed documents`() {
        create("a1", "a")
        create("a2", "a", now.plusSeconds(1), retry = now.plusSeconds(3600))
        create("a3", "a", now.plusSeconds(2))
        create("completed", "a", status = OutboxRecordStatus.COMPLETED)
        create("failed", "a", status = OutboxRecordStatus.FAILED)

        val page = repository.findIncompleteRecordPages(setOf("a")).getValue("a")

        assertThat(page.map { it.id }).containsExactly("a1", "a2", "a3")
        assertThat(page.nextRetryAt(1)).isEqualTo(now.plusSeconds(3600))
        assertThat(page.isRetryDue(1, clock)).isFalse()
    }

    @Test
    fun `metadata access does not decode and cached records retain mutations`() {
        create("a1", "a")
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
        mongoTemplate.updateFirst(byId("bad"), Update().set("recordType", "missing.Payload"), collectionName)
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
        mongoTemplate.updateFirst(byId("a2"), Update().set("payload", "not-json"), collectionName)
        mongoTemplate.updateFirst(byId("b2"), Update().set("context", "not-json"), collectionName)

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

        val pages = repository.findIncompleteRecordPages(keys.toSet())

        keys.forEachIndexed { index, key ->
            assertThat(pages.getValue(key).map { it.id }).containsExactly("record-$index")
        }
    }

    @Test
    fun `collection prefixes isolate bulk reads for the same storage key`() {
        val orders = repository("orders_")
        val billing = repository("billing_")
        create("orders-record", "shared-key", target = orders)
        create("billing-record", "shared-key", target = billing)

        val orderPages = orders.findIncompleteRecordPages(setOf("shared-key", "missing"))
        val billingPages = billing.findIncompleteRecordPages(setOf("shared-key", "missing"))

        assertThat(orderPages.getValue("shared-key").map { it.id }).containsExactly("orders-record")
        assertThat(billingPages.getValue("shared-key").map { it.id }).containsExactly("billing-record")
        assertThat(orderPages.getValue("missing")).isEmpty()
        assertThat(billingPages.getValue("missing")).isEmpty()
        assertThat(mongoTemplate.collectionExists(collectionName)).isFalse()
    }

    @Test
    fun `existing pages remain snapshots while the next read sees completion and later inserts`() {
        val first = create("a1", "a")
        val page = repository.findIncompleteRecordPages(setOf("a")).getValue("a")
        repository.updateProcessingState(completed(first))
        create("a2", "a", now.plusSeconds(1))

        assertThat(page.nextRetryAt(0)).isEqualTo(now)
        assertThat(page[0].status).isEqualTo(OutboxRecordStatus.NEW)
        assertThat(repository.findIncompleteRecordPages(setOf("a")).getValue("a").map { it.id })
            .containsExactly("a2")
    }

    @Test
    fun `pages decode on another thread after their transaction ends without further database reads`() {
        create("a1", "a")
        val transactions = TransactionTemplate(MongoTransactionManager(mongoTemplate.mongoDatabaseFactory))
        val page = checkNotNull(transactions.execute { repository.findIncompleteRecordPages(setOf("a")).getValue("a") })
        val readsAfterFetch = commands.reads.get()
        val workers = Executors.newSingleThreadExecutor()
        try {
            val record = workers.submit<OutboxRecord<*>> { page[0] }.get(10, SECONDS)

            assertThat(record.payload).isEqualTo("payload-a1")
            assertThat(record.context).containsEntry("trace", "original")
            assertThat(page[0]).isSameAs(record)
            assertThat(commands.reads.get()).isEqualTo(readsAfterFetch)
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun `processing state updates from a fetched page participate in transaction rollback`() {
        create("a1", "a")
        val transactions = TransactionTemplate(MongoTransactionManager(mongoTemplate.mongoDatabaseFactory))

        transactions.executeWithoutResult { transaction ->
            val page = repository.findIncompleteRecordPages(setOf("a")).getValue("a")
            assertThat(repository.updateProcessingState(completed(page[0]))).isTrue()
            assertThat(repository.findIncompleteRecordPages(setOf("a")).getValue("a")).isEmpty()
            transaction.setRollbackOnly()
        }

        assertThat(repository.findIncompleteRecordPages(setOf("a")).getValue("a").map { it.id }).containsExactly("a1")
        assertThat(repository.findCompletedRecords()).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `real workers preserve retry handling and persist completion before advancing a key`(strict: Boolean) {
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
    }

    @Test
    fun `a completion write failure stops its key while another key completes in order`() {
        create("a1", "a", now.minusSeconds(2))
        create("a2", "a", now.minusSeconds(1))
        create("b1", "b", now.minusSeconds(2))
        create("b2", "b", now.minusSeconds(1))
        val validator =
            Criteria()
                .orOperator(
                    Criteria.where("_id").ne("a1"),
                    Criteria.where("status").ne(OutboxRecordStatus.COMPLETED.name),
                ).criteriaObject
        mongoTemplate.db.runCommand(Document("collMod", collectionName).append("validator", validator))
        val handled = Collections.synchronizedList(mutableListOf<String>())

        runProcessingCycle(strict = true) { record -> handled.add(record.id) }

        assertThat(handled).containsExactlyInAnyOrder("a1", "b1", "b2")
        assertThat(handled.filter { it.startsWith("b") }).containsExactly("b1", "b2")
        assertThat(repository.findCompletedRecords().map { it.id }).containsExactly("b1", "b2")
        val pending = repository.findIncompleteRecordsByRecordKey("a")
        assertThat(pending.map { it.id }).containsExactly("a1", "a2")
        assertThat(pending.first().failureCount).isZero()
    }

    @Test
    fun `a decoding failure stops successors while the valid prefix and other keys complete`() {
        create("a1", "a", now.minusSeconds(2))
        create("a2", "a", now.minusSeconds(1))
        create("a3", "a")
        create("b1", "b")
        mongoTemplate.updateFirst(byId("a2"), Update().set("payload", "not-json"), collectionName)
        val handled = Collections.synchronizedList(mutableListOf<String>())

        runProcessingCycle(strict = true) { record -> handled.add(record.id) }

        assertThat(handled).containsExactlyInAnyOrder("a1", "b1")
        assertThat(repository.findCompletedRecords().map { it.id }).containsExactlyInAnyOrder("a1", "b1")
        val page = repository.findIncompleteRecordPages(setOf("a")).getValue("a")
        assertThat((0 until page.size).map { page.recordId(it) }).containsExactly("a2", "a3")
    }

    @Test
    fun `empty requests avoid finds and the shared key bound is enforced`() {
        assertThat(repository.findIncompleteRecordPages(emptySet())).isEmpty()
        assertThat(commands.finds.get()).isZero()
        assertThat(repository.findIncompleteRecordPages((1..1000).map { "missing-$it" }.toSet())).hasSize(1000)
        assertThat(commands.finds.get()).isEqualTo(1)
        assertThatThrownBy { repository.findIncompleteRecordPages((1..1001).map { "missing-$it" }.toSet()) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(commands.finds.get()).isEqualTo(1)
    }

    private fun repository(prefix: String = "") =
        MongoOutboxRecordRepository(
            mongoTemplate = mongoTemplate,
            entityMapper = MongoOutboxRecordEntityMapper(serializer),
            clock = clock,
            collectionNameResolver =
                MongoCollectionNameResolver(
                    MongoOutboxConfigurationProperties(collectionPrefix = prefix),
                ),
        )

    private fun byId(id: String) = Query(Criteria.where("_id").`is`(id))

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

    private fun create(
        id: String,
        key: String,
        created: Instant = now,
        retry: Instant = now,
        status: OutboxRecordStatus = OutboxRecordStatus.NEW,
        target: OutboxRecordRepository = repository,
    ): OutboxRecord<String> =
        target.create(
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

    private class ReadCommandCounter : CommandListener {
        val finds = AtomicInteger()
        val reads = AtomicInteger()

        override fun commandStarted(event: CommandStartedEvent) {
            if (event.commandName == "find") finds.incrementAndGet()
            if (event.commandName in setOf("find", "getMore", "aggregate")) reads.incrementAndGet()
        }
    }

    companion object {
        @Container
        @JvmStatic
        val mongodb = MongoDBContainer("mongo:9.0.4").withReplicaSet()
    }
}
