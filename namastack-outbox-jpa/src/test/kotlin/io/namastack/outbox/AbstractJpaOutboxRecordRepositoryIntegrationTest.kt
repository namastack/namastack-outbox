package io.namastack.outbox

import io.micrometer.observation.ObservationRegistry
import io.mockk.every
import io.mockk.mockk
import io.namastack.outbox.OutboxRecordStatus.COMPLETED
import io.namastack.outbox.OutboxRecordStatus.FAILED
import io.namastack.outbox.OutboxRecordStatus.NEW
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
abstract class AbstractJpaOutboxRecordRepositoryIntegrationTest {
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
    fun `findIncompleteRecordPages uses one query and loads no managed entities`() {
        create("a1", "a")
        create("b1", "b")
        flushAndClear()
        statistics.clear()

        val pages = repository.findIncompleteRecordPages(setOf("b", "a", "missing"))
        pages.getValue("a").recordIds()
        pages.getValue("b").recordIds()

        assertThat(statistics.prepareStatementCount).isEqualTo(1)
        assertThat(statistics.entityLoadCount).isZero()
    }

    @Test
    fun `findIncompleteRecordPages keeps key boundaries and orders by creation time`() {
        create("a3", "a", now.plusSeconds(1))
        create("b1", "b")
        create("a2", "a", now.minusSeconds(1))
        create("a1", "a", now.minusSeconds(2))
        flushAndClear()

        val pages = repository.findIncompleteRecordPages(setOf("b", "a"))

        assertThat(pages.keys).containsExactlyInAnyOrder("a", "b")
        assertThat(pages.getValue("a").recordIds()).containsExactly("a1", "a2", "a3")
        assertThat(pages.getValue("b").recordIds()).containsExactly("b1")
    }

    @Test
    fun `findIncompleteRecordPages orders records with equal creation time by id`() {
        create("c", "a")
        create("a", "a")
        create("b", "a")
        flushAndClear()

        assertThat(repository.findIncompleteRecordPages(setOf("a")).getValue("a").recordIds())
            .containsExactly("a", "b", "c")
    }

    @Test
    fun `findIncompleteRecordPages returns an empty page for a key without records`() {
        assertThat(repository.findIncompleteRecordPages(setOf("missing")).getValue("missing").isEmpty()).isTrue()
    }

    @Test
    fun `findIncompleteRecordPages returns the same order as findIncompleteRecordsByRecordKey`() {
        create("a3", "a", now.plusSeconds(1))
        create("a2", "a")
        create("a1", "a")
        flushAndClear()

        assertThat(repository.findIncompleteRecordPages(setOf("a")).getValue("a").recordIds())
            .containsExactlyElementsOf(repository.findIncompleteRecordsByRecordKey("a").map { it.id })
    }

    @Test
    fun `findIncompleteRecordPages returns future retries and excludes completed and failed records`() {
        create("a1", "a")
        create("a2", "a", now.plusSeconds(1), retry = now.plusSeconds(3600))
        create("a3", "a", now.plusSeconds(2))
        create("completed", "a", status = COMPLETED)
        create("failed", "a", status = FAILED)
        flushAndClear()

        val page = repository.findIncompleteRecordPages(setOf("a")).getValue("a")

        assertThat(page.recordIds()).containsExactly("a1", "a2", "a3")
        assertThat(page[1].nextRetryAt).isEqualTo(now.plusSeconds(3600))
    }

    @Test
    fun `findIncompleteRecordPages decodes a record only on first access`() {
        create("a1", "a")
        flushAndClear()
        serializer.reset()

        val page = repository.findIncompleteRecordPages(setOf("a")).getValue("a")

        assertThat(page.size).isEqualTo(1)
        assertThat(serializer.deserializations).isZero()
        assertThat(serializer.serializations).isZero()

        val record = page[0]
        assertThat(record.id).isEqualTo("a1")
        assertThat(record.nextRetryAt).isEqualTo(now)
        assertThat(record.payload).isEqualTo("payload-a1")
        assertThat(record.context).containsEntry("trace", "original")
        assertThat(serializer.deserializations).isEqualTo(2)
    }

    @Test
    fun `findIncompleteRecordPages caches a decoded record with its mutations`() {
        create("a1", "a")
        flushAndClear()
        val page = repository.findIncompleteRecordPages(setOf("a")).getValue("a")
        val record = page[0]
        assertThat(record.context).containsEntry("trace", "original")
        val deserializations = serializer.deserializations

        (record.context as MutableMap<String, String>)["trace"] = "mutated"

        assertThat(page[0]).isSameAs(record)
        assertThat(page[0].context).containsEntry("trace", "mutated")
        assertThat(serializer.deserializations).isEqualTo(deserializations)
    }

    @Test
    fun `findIncompleteRecordPages loads pages with an unavailable payload type and fails on its access`() {
        create("bad", "a", retry = now.plusSeconds(60))
        create("good", "b")
        entityManager.flush()
        entityManager
            .createQuery("update OutboxRecordEntity o set o.recordType = 'missing.Payload' where o.id = 'bad'")
            .executeUpdate()
        entityManager.clear()
        serializer.reset()

        val pages = repository.findIncompleteRecordPages(setOf("a", "b"))

        assertThat(pages.getValue("a").size).isEqualTo(1)
        assertThat(serializer.deserializations).isZero()
        assertThat(pages.getValue("b")[0].id).isEqualTo("good")
        assertThatThrownBy { pages.getValue("a")[0] }
            .isInstanceOf(OutboxPayloadTypeNotFoundException::class.java)
    }

    @Test
    fun `findIncompleteRecordPages keeps the valid prefix readable before a malformed payload or context`() {
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
    fun `findIncompleteRecordPages keeps processing group storage keys separate`() {
        val keys = listOf("order:42", "~ns.pg1~billing:order:42", "~ns.pg1~audit:order:42", "~ns.pg1~billing:订单:ä:😀")
        keys.forEachIndexed { index, key -> create("record-$index", key) }
        flushAndClear()

        val pages = repository.findIncompleteRecordPages(keys.toSet())

        keys.forEachIndexed { index, key ->
            assertThat(pages.getValue(key).recordIds()).containsExactly("record-$index")
        }
    }

    @Test
    fun `findIncompleteRecordPages keeps an earlier page stable and the next read sees progress`() {
        val first = create("a1", "a")
        flushAndClear()
        val managed = entityManager.find(OutboxRecordEntity::class.java, first.id)
        val page = repository.findIncompleteRecordPages(setOf("a")).getValue("a")

        repository.update(completed(first))
        create("a2", "a", now.plusSeconds(1))
        entityManager.flush()

        assertThat(entityManager.contains(managed)).isTrue()
        assertThat(managed.status).isEqualTo(COMPLETED)
        assertThat(page[0].nextRetryAt).isEqualTo(now)
        assertThat(page[0].status).isEqualTo(NEW)
        assertThat(repository.findIncompleteRecordPages(setOf("a")).getValue("a").recordIds())
            .containsExactly("a2")
    }

    @Test
    @Transactional(propagation = NOT_SUPPORTED)
    fun `findIncompleteRecordPages fetched without a transaction decodes on another thread without database access`() {
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

    @Test
    fun `findIncompleteRecordPages runs no query for an empty request`() {
        statistics.clear()

        assertThat(repository.findIncompleteRecordPages(emptySet())).isEmpty()
        assertThat(statistics.prepareStatementCount).isZero()
    }

    @Test
    fun `findIncompleteRecordPages accepts 1000 keys and rejects more`() {
        assertThat(repository.findIncompleteRecordPages((1..1000).map { "missing-$it" }.toSet())).hasSize(1000)
        assertThatThrownBy { repository.findIncompleteRecordPages((1..1001).map { "missing-$it" }.toSet()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `findIncompleteRecordPages returns an empty page for a selected key that was deleted`() {
        create("remaining", "remaining")
        create("disappeared", "disappeared")
        flushAndClear()
        val selected = repository.findRecordKeysInPartitions(setOf(1), 10, true).toSet()
        repository.deleteById("disappeared")
        flushAndClear()
        statistics.clear()

        val pages = repository.findIncompleteRecordPages(selected)

        assertThat(pages.getValue("remaining").recordIds()).containsExactly("remaining")
        assertThat(pages.getValue("disappeared").isEmpty()).isTrue()
        assertThat(statistics.prepareStatementCount).isEqualTo(1)
    }

    @Test
    fun `findIncompleteRecordsByRecordKey orders records with equal creation time by id`() {
        create("c", "a")
        create("a", "a")
        create("b", "a")
        flushAndClear()

        assertThat(repository.findIncompleteRecordsByRecordKey("a").map { it.id }).containsExactly("a", "b", "c")
    }

    @Test
    fun `findIncompleteRecordsByRecordKey returns future retries and excludes completed and failed records`() {
        create("n1", "a", now.minusSeconds(2))
        create("n2", "a", now.minusSeconds(1), retry = now.plusSeconds(3600))
        create("c1", "a", now.minusSeconds(3), status = COMPLETED)
        create("f1", "a", now.minusSeconds(4), status = FAILED)
        flushAndClear()

        assertThat(repository.findIncompleteRecordsByRecordKey("a").map { it.id }).containsExactly("n1", "n2")
    }

    @Test
    fun `findPendingRecords returns new records oldest first`() {
        create("n2", "a")
        create("n1", "b", now.minusSeconds(10))
        create("f1", "c", status = FAILED)
        create("c1", "d", status = COMPLETED)
        flushAndClear()

        assertThat(repository.findPendingRecords().map { it.id }).containsExactly("n1", "n2")
    }

    @Test
    fun `findRecordKeysInPartitions in strict mode returns keys whose first incomplete record is due in order`() {
        seedHeadOfKeyScenario()

        assertThat(repository.findRecordKeysInPartitions(setOf(1), 10, true))
            .containsExactly("tie-z", "tie-a", "a", "d")
    }

    @Test
    fun `findRecordKeysInPartitions in strict mode honours the batch size`() {
        seedHeadOfKeyScenario()

        assertThat(repository.findRecordKeysInPartitions(setOf(1), 3, true))
            .containsExactly("tie-z", "tie-a", "a")
    }

    @Test
    fun `findRecordKeysInPartitions in strict mode is blocked by an earlier equal time record`() {
        create("a1", "a", now.minusSeconds(5), retry = now.plusSeconds(3600))
        create("a2", "a", now.minusSeconds(5))

        assertThat(repository.findRecordKeysInPartitions(setOf(1), 10, true)).isEmpty()
    }

    @Test
    fun `findRecordKeysInPartitions in non strict mode orders keys by their first due record`() {
        seedHeadOfKeyScenario()

        assertThat(repository.findRecordKeysInPartitions(setOf(1), 10, false))
            .containsExactly("tie-z", "tie-a", "a", "d", "b", "c")
    }

    @Test
    fun `findRecordKeysInPartitions in non strict mode honours the batch size`() {
        seedHeadOfKeyScenario()

        assertThat(repository.findRecordKeysInPartitions(setOf(1), 5, false))
            .containsExactly("tie-z", "tie-a", "a", "d", "b")
    }

    @Test
    fun `findRecordKeysInPartitions in non strict mode is not blocked by an earlier record that is not due`() {
        create("a1", "a", now.minusSeconds(5), retry = now.plusSeconds(3600))
        create("a2", "a", now.minusSeconds(5))

        assertThat(repository.findRecordKeysInPartitions(setOf(1), 10, false)).containsExactly("a")
    }

    @Test
    fun `findRecordKeysInPartitions returns nothing for partitions without records`() {
        seedHeadOfKeyScenario()

        listOf(true, false).forEach { strict ->
            assertThat(repository.findRecordKeysInPartitions(setOf(2, 3), 10, strict)).isEmpty()
        }
    }

    @Test
    fun `findRecordKeysInPartitions returns nothing without records`() {
        listOf(true, false).forEach { strict ->
            assertThat(repository.findRecordKeysInPartitions(setOf(1), 10, strict)).isEmpty()
        }
    }

    @Test
    fun `countByStatus counts only the requested status`() {
        create("n1", "a")
        create("n2", "b")
        create("f1", "c", status = FAILED)
        create("c1", "d", status = COMPLETED)

        assertThat(repository.countByStatus(NEW)).isEqualTo(2)
        assertThat(repository.countByStatus(FAILED)).isEqualTo(1)
        assertThat(repository.countByStatus(COMPLETED)).isEqualTo(1)
    }

    @Test
    fun `countByStatuses counts incomplete statuses`() {
        create("n1", "a")
        create("n2", "b")
        create("f1", "c", status = FAILED)
        create("c1", "d", status = COMPLETED)

        assertThat(repository.countByStatuses(setOf(NEW, FAILED)))
            .containsExactlyInAnyOrderEntriesOf(mapOf(NEW to 2L, FAILED to 1L))
    }

    @Test
    fun `countByStatuses counts completed alone`() {
        create("n1", "a")
        create("c1", "b", status = COMPLETED)
        create("c2", "c", status = COMPLETED)

        assertThat(repository.countByStatuses(setOf(COMPLETED))).containsExactlyEntriesOf(mapOf(COMPLETED to 2L))
    }

    @Test
    fun `countByStatuses counts completed mixed with incomplete statuses`() {
        create("n1", "a")
        create("f1", "b", status = FAILED)
        create("c1", "c", status = COMPLETED)
        create("c2", "d", status = COMPLETED)

        assertThat(repository.countByStatuses(setOf(NEW, COMPLETED)))
            .containsExactlyInAnyOrderEntriesOf(mapOf(NEW to 1L, COMPLETED to 2L))
        assertThat(repository.countByStatuses(OutboxRecordStatus.entries.toSet()))
            .containsExactlyInAnyOrderEntriesOf(mapOf(NEW to 1L, FAILED to 1L, COMPLETED to 2L))
    }

    @Test
    fun `countByStatuses returns zero for requested statuses without records`() {
        create("n1", "a")

        assertThat(repository.countByStatuses(setOf(COMPLETED))).containsExactlyEntriesOf(mapOf(COMPLETED to 0L))
        assertThat(repository.countByStatuses(setOf(NEW, FAILED)))
            .containsExactlyInAnyOrderEntriesOf(mapOf(NEW to 1L, FAILED to 0L))
    }

    @Test
    fun `countByStatuses returns nothing when no status is requested`() {
        assertThat(repository.countByStatuses(emptySet())).isEmpty()
    }

    @Test
    fun `countRecordsByPartition counts one partition and status`() {
        create("n1", "a", partition = 1)
        create("n2", "b", partition = 2)
        create("f1", "c", partition = 1, status = FAILED)

        assertThat(repository.countRecordsByPartition(1, NEW)).isEqualTo(1)
        assertThat(repository.countRecordsByPartition(2, NEW)).isEqualTo(1)
        assertThat(repository.countRecordsByPartition(2, FAILED)).isZero()
    }

    @Test
    fun `countRecordsByPartitions counts every status across the partitions`() {
        create("n1", "a", partition = 1)
        create("n2", "b", partition = 2)
        create("n3", "c", partition = 3)
        create("f1", "d", partition = 1, status = FAILED)
        create("c1", "e", partition = 1, status = COMPLETED)
        create("c2", "f", partition = 2, status = COMPLETED)

        assertThat(repository.countRecordsByPartitions(setOf(1, 2), NEW)).isEqualTo(2)
        assertThat(repository.countRecordsByPartitions(setOf(1, 2), FAILED)).isEqualTo(1)
        assertThat(repository.countRecordsByPartitions(setOf(1, 2), COMPLETED)).isEqualTo(2)
    }

    @Test
    fun `countRecordsByPartitions returns zero for partitions without records and for no partitions`() {
        create("c1", "a", partition = 1, status = COMPLETED)

        assertThat(repository.countRecordsByPartitions(setOf(2), COMPLETED)).isZero()
        assertThat(repository.countRecordsByPartitions(emptySet(), COMPLETED)).isZero()
    }

    @Test
    fun `findOldestCreatedAtByPartitions returns the oldest new record regardless of its retry time`() {
        seedHeadOfKeyScenario()

        assertThat(repository.findOldestCreatedAtByPartitions(setOf(1))).isEqualTo(now.minusSeconds(30))
    }

    @Test
    fun `findOldestCreatedAtByPartitions returns null for partitions without records`() {
        seedHeadOfKeyScenario()

        assertThat(repository.findOldestCreatedAtByPartitions(setOf(2, 3))).isNull()
    }

    @Test
    fun `findOldestCreatedAtByPartitions returns null without records`() {
        assertThat(repository.findOldestCreatedAtByPartitions(setOf(1))).isNull()
    }

    @Test
    fun `findOldestDueNextRetryAtByPartitions in strict mode skips blocked records`() {
        seedHeadOfKeyScenario()

        assertThat(repository.findOldestDueNextRetryAtByPartitions(setOf(1), now, true))
            .isEqualTo(now.minusSeconds(15))
    }

    @Test
    fun `findOldestDueNextRetryAtByPartitions in strict mode is blocked by an earlier equal time record`() {
        create("a1", "a", now.minusSeconds(5), retry = now.plusSeconds(3600))
        create("a2", "a", now.minusSeconds(5))

        assertThat(repository.findOldestDueNextRetryAtByPartitions(setOf(1), now, true)).isNull()
    }

    @Test
    fun `findOldestDueNextRetryAtByPartitions in non strict mode includes blocked records`() {
        seedHeadOfKeyScenario()

        assertThat(repository.findOldestDueNextRetryAtByPartitions(setOf(1), now, false))
            .isEqualTo(now.minusSeconds(50))
    }

    @Test
    fun `findOldestDueNextRetryAtByPartitions returns null when no record is due yet`() {
        seedHeadOfKeyScenario()

        listOf(true, false).forEach { strict ->
            assertThat(repository.findOldestDueNextRetryAtByPartitions(setOf(1), now.minusSeconds(60), strict)).isNull()
        }
    }

    @Test
    fun `findOldestDueNextRetryAtByPartitions returns null for partitions without records`() {
        seedHeadOfKeyScenario()

        listOf(true, false).forEach { strict ->
            assertThat(repository.findOldestDueNextRetryAtByPartitions(setOf(2, 3), now, strict)).isNull()
        }
    }

    @Test
    fun `findOldestDueNextRetryAtByPartitions returns null without records`() {
        listOf(true, false).forEach { strict ->
            assertThat(repository.findOldestDueNextRetryAtByPartitions(setOf(1), now, strict)).isNull()
        }
    }

    @Test
    fun `updateProcessingState persists the state without changing payload or context`() {
        val record = create("a1", "a")
        flushAndClear()

        val updated =
            repository.updateProcessingState(
                OutboxRecord.restore(
                    id = record.id,
                    recordKey = record.key,
                    payload = "changed",
                    context = mapOf("changed" to "true"),
                    partition = record.partition,
                    createdAt = record.createdAt,
                    status = COMPLETED,
                    completedAt = now,
                    failureCount = 2,
                    failureException = null,
                    failureReason = "boom",
                    nextRetryAt = now.plusSeconds(60),
                    handlerId = record.handlerId,
                ),
            )
        flushAndClear()

        val stored = repository.findCompletedRecords().single()
        assertThat(updated).isTrue()
        assertThat(stored.completedAt).isEqualTo(now)
        assertThat(stored.failureCount).isEqualTo(2)
        assertThat(stored.failureReason).isEqualTo("boom")
        assertThat(stored.nextRetryAt).isEqualTo(now.plusSeconds(60))
        assertThat(stored.payload).isEqualTo("payload-a1")
        assertThat(stored.context).containsEntry("trace", "original")
    }

    @Test
    fun `findCompletedRecords returns at most the limit oldest first`() {
        seedRetentionScenario()

        assertThat(repository.findCompletedRecords(now, 2).map { it.id }).containsExactly("c1", "c2")
    }

    @Test
    fun `findFailedRecords returns at most the limit oldest first`() {
        seedRetentionScenario()

        assertThat(repository.findFailedRecords(now, 1).map { it.id }).containsExactly("f1")
    }

    @Test
    fun `deleteCompletedRecords deletes at most the limit oldest first`() {
        seedRetentionScenario()

        assertThat(repository.deleteCompletedRecords(now, 2)).isEqualTo(2)
        flushAndClear()

        assertThat(repository.findCompletedRecords().map { it.id }).containsExactly("c3")
    }

    @Test
    fun `deleteFailedRecords deletes at most the limit oldest first`() {
        seedRetentionScenario()

        assertThat(repository.deleteFailedRecords(now, 1)).isEqualTo(1)
        flushAndClear()

        assertThat(repository.findFailedRecords().map { it.id }).containsExactly("f2")
    }

    @Test
    fun `deleteByStatus deletes only the requested status`() {
        create("n1", "a")
        create("c1", "b", status = COMPLETED)
        flushAndClear()

        repository.deleteByStatus(COMPLETED)
        flushAndClear()

        assertThat(repository.findCompletedRecords()).isEmpty()
        assertThat(repository.findPendingRecords().map { it.id }).containsExactly("n1")
    }

    @Test
    fun `deleteByRecordKeyAndStatus deletes only the requested key and status`() {
        create("a1", "a")
        create("a2", "a", status = FAILED)
        create("b1", "b")
        flushAndClear()

        repository.deleteByRecordKeyAndStatus("a", NEW)
        flushAndClear()

        assertThat(repository.findPendingRecords().map { it.id }).containsExactly("b1")
        assertThat(repository.findFailedRecords().map { it.id }).containsExactly("a2")
    }

    @Test
    fun `deleteById deletes only the requested record`() {
        create("a1", "a")
        create("a2", "a", now.plusSeconds(1))
        flushAndClear()

        repository.deleteById("a1")
        flushAndClear()

        assertThat(repository.findPendingRecords().map { it.id }).containsExactly("a2")
    }

    @Test
    fun `deleteByIds deletes only the requested records and returns their count`() {
        create("a1", "a")
        create("a2", "b")
        create("a3", "c")
        flushAndClear()

        assertThat(repository.deleteByIds(listOf("a1", "a2", "missing"))).isEqualTo(2)
        flushAndClear()

        assertThat(repository.findPendingRecords().map { it.id }).containsExactly("a3")
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    @Transactional(propagation = NOT_SUPPORTED)
    fun `processing cycle preserves strict and non strict retry behavior and commits before successors`(
        strict: Boolean,
    ) {
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
            assertThat(repository.findRecordKeysInPartitions(setOf(1), 10, strict)).isEmpty()
        } finally {
            deleteAllRecords()
        }
    }

    @Test
    @Transactional(propagation = NOT_SUPPORTED)
    fun `processing cycle stops a key on a completion persistence failure while another key completes in order`() {
        var constraintAdded = false
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
            constraintAdded = true
            val handled = Collections.synchronizedList(mutableListOf<String>())

            runProcessingCycle(strict = true) { record -> handled.add(record.id) }

            assertThat(handled).containsExactlyInAnyOrder("a1", "b1", "b2")
            assertThat(handled.filter { it.startsWith("b") }).containsExactly("b1", "b2")
            assertThat(repository.findCompletedRecords().map { it.id }).containsExactly("b1", "b2")
            val pending = repository.findIncompleteRecordsByRecordKey("a")
            assertThat(pending.map { it.id }).containsExactly("a1", "a2")
            assertThat(pending.first().failureCount).isZero()
        } finally {
            if (constraintAdded) {
                transactions.executeWithoutResult {
                    entityManager
                        .createNativeQuery(
                            "ALTER TABLE outbox_record DROP CONSTRAINT reject_bulk_completion",
                        ).executeUpdate()
                }
            }
            deleteAllRecords()
        }
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

    /**
     * Seeds partition 1 with:
     * - `tie-z` and `tie-a`: equal creation time, ordered by id (`t-1` before `t-2`)
     * - `a`: due head; `d`: due head after a completed record
     * - `b`: failed head blocking a record due since `now - 50s`
     * - `c`: head not yet due blocking a later due record
     */
    private fun seedHeadOfKeyScenario() {
        create("t-2", "tie-a", now.minusSeconds(15), retry = now.minusSeconds(15))
        create("t-1", "tie-z", now.minusSeconds(15), retry = now.minusSeconds(15))
        create("a1", "a", now.minusSeconds(10), retry = now.minusSeconds(10))
        create("d1", "d", now.minusSeconds(40), status = COMPLETED)
        create("d2", "d", now.minusSeconds(8), retry = now.minusSeconds(8))
        create("b1", "b", now.minusSeconds(20), retry = now.minusSeconds(20), status = FAILED)
        create("b2", "b", now.minusSeconds(5), retry = now.minusSeconds(50))
        create("c1", "c", now.minusSeconds(30), retry = now.plusSeconds(3600))
        create("c2", "c", now.minusSeconds(1), retry = now.minusSeconds(1))
    }

    private fun seedRetentionScenario() {
        create("c3", "a", status = COMPLETED, completed = now.minusSeconds(10))
        create("c1", "b", now.plusSeconds(1), status = COMPLETED, completed = now.minusSeconds(30))
        create("c2", "c", now.plusSeconds(2), status = COMPLETED, completed = now.minusSeconds(20))
        create("f2", "d", retry = now.minusSeconds(20), status = FAILED)
        create("f1", "e", now.plusSeconds(1), retry = now.minusSeconds(30), status = FAILED)
    }

    private fun create(
        id: String,
        key: String,
        created: Instant = now,
        retry: Instant = now,
        status: OutboxRecordStatus = NEW,
        completed: Instant = now,
        partition: Int = 1,
    ): OutboxRecord<String> =
        repository.create(
            OutboxRecord.restore(
                id = id,
                recordKey = key,
                payload = "payload-$id",
                context = mapOf("trace" to "original"),
                partition = partition,
                createdAt = created,
                status = status,
                completedAt = if (status == COMPLETED) completed else null,
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
            status = COMPLETED,
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

    private fun OutboxRecordPage.recordIds(): List<String> = (0 until size).map { get(it).id }
}
