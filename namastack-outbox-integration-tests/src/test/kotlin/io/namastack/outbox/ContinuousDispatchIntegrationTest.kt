package io.namastack.outbox

import io.namastack.outbox.handler.OutboxHandler
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.partition.PartitionHasher
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.test.context.TestPropertySource
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Integration test for continuous dispatch.
 *
 * Scenario:
 * - Many record keys with several records each, plus one slow key, and a batch size smaller than the key count
 * - Verifies that every record is delivered exactly once, in creation order per key, and that a key is
 *   never handled concurrently
 * - Verifies that the other keys are delivered while one key is blocked, which a batch barrier prevents
 */
@OutboxIntegrationTest
@Import(ContinuousDispatchIntegrationTest.TestProcessor::class)
@TestPropertySource(
    properties = [
        "namastack.outbox.processing.dispatch-mode=continuous",
        "namastack.outbox.polling.batch-size=4",
        "namastack.outbox.processing.executor-core-pool-size=4",
    ],
)
class ContinuousDispatchIntegrationTest {
    @Autowired
    private lateinit var outboxRecordRepository: OutboxRecordRepository

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    private lateinit var jdbcClient: JdbcClient

    @Autowired
    private lateinit var processingScheduler: OutboxProcessingScheduler

    @Autowired
    private lateinit var processor: TestProcessor

    @BeforeEach
    fun setUp() = processor.reset()

    @AfterEach
    fun cleanup() {
        processor.releaseBlockedKey()
        transactionTemplate.executeWithoutResult {
            jdbcClient.sql("DELETE FROM outbox_record").update()
            jdbcClient.sql("DELETE FROM outbox_instance").update()
            jdbcClient.sql("DELETE FROM outbox_partition").update()
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `delivers every record exactly once in per-key order`() {
        val keyDispatcher = ReflectionTestUtils.getField(processingScheduler, "keyDispatcher")
        assertThat(keyDispatcher?.javaClass?.simpleName).isEqualTo("ContinuousKeyDispatcher")

        val keys = (1..20).map { "key-$it" } + SLOW_KEY
        val recordsPerKey = 5
        val base = Instant.now().minusSeconds(60)
        var sequence = 0
        transactionTemplate.executeWithoutResult {
            for (index in 0 until recordsPerKey) {
                keys.forEach { key ->
                    sequence++
                    saveRecord(
                        id = "00000000-0000-4000-8000-%012d".format(sequence),
                        key = key,
                        payload = "$key:$index",
                        createdAt = base.plusMillis(sequence.toLong()),
                    )
                }
            }
        }
        val total = keys.size * recordsPerKey

        await().atMost(30, SECONDS).untilAsserted {
            assertThat(outboxRecordRepository.findCompletedRecords()).hasSize(total)
        }

        assertThat(processor.handled).hasSize(total)
        assertThat(processor.handled).doesNotHaveDuplicates()
        keys.forEach { key ->
            assertThat(processor.handledByKey.getValue(key))
                .describedAs("handling order of %s", key)
                .containsExactlyElementsOf((0 until recordsPerKey).toList())
        }
        assertThat(processor.concurrentKeyHandling.get()).describedAs("a key was handled concurrently").isFalse()
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `delivers other keys while one key is blocked`() {
        processor.blockKey(SLOW_KEY)
        val otherKeys = (1..12).map { "key-$it" }
        val base = Instant.now().minusSeconds(60)
        transactionTemplate.executeWithoutResult {
            // The blocked key is the oldest, so it is part of the first selected batch.
            (listOf(SLOW_KEY) + otherKeys).forEachIndexed { index, key ->
                saveRecord(
                    id = "00000000-0000-4000-8000-%012d".format(index + 1),
                    key = key,
                    payload = "$key:0",
                    createdAt = base.plusMillis(index.toLong()),
                )
            }
        }

        // With a batch size of 4, a batch barrier would hold all but three other keys behind the blocked key.
        await().atMost(30, SECONDS).untilAsserted {
            assertThat(processor.handledByKey.keys).containsExactlyInAnyOrderElementsOf(otherKeys)
        }
        assertThat(processor.handledByKey).doesNotContainKey(SLOW_KEY)

        processor.releaseBlockedKey()

        await().atMost(30, SECONDS).untilAsserted {
            assertThat(outboxRecordRepository.findCompletedRecords()).hasSize(otherKeys.size + 1)
        }
    }

    private fun saveRecord(
        id: String,
        key: String,
        payload: String,
        createdAt: Instant,
    ) {
        outboxRecordRepository.create(
            OutboxRecord.restore(
                id = id,
                recordKey = key,
                payload = payload,
                context = emptyMap(),
                partition = PartitionHasher.getPartitionForRecordKey(key),
                createdAt = createdAt,
                status = OutboxRecordStatus.NEW,
                completedAt = null,
                failureCount = 0,
                failureException = null,
                failureReason = null,
                nextRetryAt = createdAt,
                handlerId = HANDLER_ID,
            ),
        )
    }

    /**
     * Records each delivery; records of the slow key take noticeably longer than the others.
     */
    @Component
    class TestProcessor : OutboxHandler {
        val handled = CopyOnWriteArrayList<String>()
        val handledByKey = ConcurrentHashMap<String, MutableList<Int>>()
        val concurrentKeyHandling = AtomicBoolean(false)
        private val activeByKey = ConcurrentHashMap<String, AtomicInteger>()

        @Volatile
        private var blockedKey: String? = null
        private val blockedKeyRelease = AtomicReference(CountDownLatch(0))

        fun blockKey(key: String) {
            blockedKeyRelease.set(CountDownLatch(1))
            blockedKey = key
        }

        fun releaseBlockedKey() {
            blockedKeyRelease.get().countDown()
        }

        fun reset() {
            blockedKey = null
            releaseBlockedKey()
            handled.clear()
            handledByKey.clear()
            concurrentKeyHandling.set(false)
            activeByKey.clear()
        }

        override fun handle(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) {
            val (key, index) = (payload as String).split(":")
            val active = activeByKey.computeIfAbsent(key) { AtomicInteger() }
            if (active.incrementAndGet() > 1) concurrentKeyHandling.set(true)
            try {
                if (key == blockedKey) {
                    blockedKeyRelease.get().await(30, SECONDS)
                } else if (key == SLOW_KEY) {
                    Thread.sleep(200)
                }
                handledByKey.computeIfAbsent(key) { CopyOnWriteArrayList() }.add(index.toInt())
                handled.add(payload)
            } finally {
                active.decrementAndGet()
            }
        }
    }

    @SpringBootApplication
    class TestApplication

    private companion object {
        const val SLOW_KEY = "slow-key"
        val HANDLER_ID =
            "${TestProcessor::class.java.name}#handle(java.lang.Object,${OutboxRecordMetadata::class.java.name})"
    }
}
