package io.namastack.outbox.dispatch

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.LazyOutboxRecordPage
import io.namastack.outbox.OutboxHandlerNotFoundException
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxRecordTestFactory
import io.namastack.outbox.instrumentation.OutboxRecordProcessingOutcome
import io.namastack.outbox.processor.OutboxRecordProcessorChainInvoker
import io.namastack.outbox.runtime.OutboxRuntimeSettings
import io.namastack.outbox.trigger.AdaptivePollingTrigger
import io.namastack.outbox.trigger.FixedPollingTrigger
import io.namastack.outbox.trigger.OutboxPollingTrigger
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.core.task.TaskExecutor
import org.springframework.core.task.support.TaskExecutorAdapter
import org.springframework.scheduling.TriggerContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

@Timeout(30)
class ContinuousKeyDispatcherTest {
    private val now = Instant.parse("2026-01-01T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private val repository = mockk<OutboxRecordRepository>()
    private val invoker = mockk<OutboxRecordProcessorChainInvoker>()
    private val trigger = mockk<OutboxPollingTrigger>(relaxed = true)

    private val workers = Executors.newCachedThreadPool()
    private val dispatchingExecutor = Executors.newSingleThreadExecutor()
    private val dispatchingThread = AtomicReference<Thread>()
    private val interruptedAfterDispatch = AtomicBoolean(false)

    /** Fake outbox table: due records per key in processing order. */
    private val pending = LinkedHashMap<String, MutableList<OutboxRecord<*>>>()

    /** Keys with a failed record; strict key queries skip them. */
    private val keysWithFailure = ConcurrentHashMap.newKeySet<String>()

    /** Result of every key query, in query order. */
    private val queries = CopyOnWriteArrayList<List<String>>()

    /** Ids of records processed, in completion order. */
    private val processed = CopyOnWriteArrayList<String>()

    /** Latches that keep a record's processing (including its completion commit) open. */
    private val blockers = ConcurrentHashMap<String, CountDownLatch>()
    private val started = ConcurrentHashMap<String, CountDownLatch>()
    private val outcomes = ConcurrentHashMap<String, () -> OutboxRecordProcessingOutcome>()
    private val afterProcessing = ConcurrentHashMap<String, () -> Unit>()

    private val activeByKey = ConcurrentHashMap<String, AtomicInteger>()
    private val concurrentKeyProcessing = AtomicBoolean(false)

    /** Keys processed at the same time, and its maximum; workers never queue, so this equals the keys in flight. */
    private val activeKeys = AtomicInteger()
    private val maxActiveKeys = AtomicInteger()
    private var batchSize = 10

    private var triggerDelay: Duration = Duration.ofMinutes(1)
    private var queryFailure: (Int) -> Exception? = { null }
    private var pageLoadFailure: (Int) -> Exception? = { null }
    private var processingDelay: (OutboxRecord<*>) -> Long = { 0 }

    @BeforeEach
    fun setUp() {
        every { trigger.nextExecution(any()) } answers {
            firstArg<TriggerContext>().lastCompletion()!!.plus(triggerDelay)
        }

        every { repository.findRecordKeysInPartitions(setOf(1), any(), any()) } answers {
            queryFailure(queries.size + 1)?.let {
                queries.add(emptyList())
                throw it
            }
            val strict = thirdArg<Boolean>()
            val keys =
                synchronized(pending) {
                    pending
                        .filter { (key, records) -> records.isNotEmpty() && !(strict && key in keysWithFailure) }
                        .keys
                        .take(secondArg())
                }
            queries.add(keys)
            keys
        }

        val pageLoads = AtomicInteger()
        every { repository.findIncompleteRecordPages(any()) } answers {
            pageLoadFailure(pageLoads.incrementAndGet())?.let { throw it }
            synchronized(pending) {
                firstArg<Set<String>>().associateWith { key ->
                    LazyOutboxRecordPage(pending[key].orEmpty().toList()) { it }
                }
            }
        }

        every { invoker.process(any()) } answers {
            val record = firstArg<OutboxRecord<*>>()
            val active = activeByKey.computeIfAbsent(record.key) { AtomicInteger() }
            if (active.incrementAndGet() > 1) concurrentKeyProcessing.set(true)
            maxActiveKeys.accumulateAndGet(activeKeys.incrementAndGet(), ::maxOf)
            try {
                started.computeIfAbsent(record.id) { CountDownLatch(1) }.countDown()
                blockers[record.id]?.await(TIMEOUT_SECONDS, SECONDS)
                processingDelay(record).takeIf { it > 0 }?.let { Thread.sleep(it) }
                val outcome = outcomes[record.id]?.invoke() ?: OutboxRecordProcessingOutcome.COMPLETED
                if (outcome != OutboxRecordProcessingOutcome.COMPLETED) keysWithFailure.add(record.key)
                synchronized(pending) { pending[record.key]?.remove(record) }
                processed.add(record.id)
                afterProcessing[record.id]?.invoke()
                outcome
            } finally {
                activeKeys.decrementAndGet()
                active.decrementAndGet()
            }
        }
    }

    @AfterEach
    fun tearDown() {
        blockers.values.forEach { it.countDown() }
        dispatchingExecutor.shutdownNow()
        workers.shutdownNow()
        assertThat(concurrentKeyProcessing.get()).describedAs("a key was processed twice at the same time").isFalse()
        assertThat(maxActiveKeys.get()).describedAs("keys in flight").isLessThanOrEqualTo(batchSize)
    }

    @Nested
    @DisplayName("Run")
    inner class Run {
        @Test
        fun `ends the run when nothing is in flight and the query finds no keys`() {
            dispatcher(batchSize = 10).dispatch(setOf(1)) { false }

            assertThat(processed).isEmpty()
            assertThat(queries).containsExactly(emptyList())
            verify(exactly = 1) { trigger.onPollResult(0, 10) }
            verify(exactly = 0) { repository.findIncompleteRecordPages(any()) }
        }

        @Test
        fun `ends the run when every key of a partial batch has finished`() {
            add("A", "a1")
            afterProcessing["a1"] = { add("A", "a2") }

            dispatcher(batchSize = 4).dispatch(setOf(1)) { false }

            assertThat(processed).containsExactly("a1")
            assertThat(queries).containsExactly(listOf("A"))
            verify(exactly = 1) { trigger.onPollResult(any(), any()) }
            verify(exactly = 1) { trigger.onPollResult(1, 4) }
        }

        @Test
        fun `selects a key again in the same run after a full batch finished`() {
            add("A", "a1")
            afterProcessing["a1"] = { add("A", "a2") }

            dispatcher(batchSize = 1).dispatch(setOf(1)) { false }

            assertThat(processed).containsExactly("a1", "a2")
            assertThat(queries).containsExactly(listOf("A"), listOf("A"), emptyList())
            verify(exactly = 2) { trigger.onPollResult(1, 1) }
            verify(exactly = 1) { trigger.onPollResult(0, 1) }
        }
    }

    @Nested
    @DisplayName("Refill")
    inner class Refill {
        @Test
        fun `refills free slots while a slow key is processed`() {
            add("A", "a1")
            val slow = block("a1")
            listOf("B", "C", "D", "E", "F", "G").forEach { add(it, it.lowercase() + "1") }

            val run = startDispatch(dispatcher(batchSize = 4))

            await().atMost(TIMEOUT_SECONDS, SECONDS).untilAsserted {
                assertThat(processed).containsExactlyInAnyOrder("b1", "c1", "d1", "e1", "f1", "g1")
            }
            assertThat(run.isDone).isFalse()

            slow.countDown()

            run.get(TIMEOUT_SECONDS, SECONDS)
            assertThat(processed).hasSize(7)
            assertThat(processed).containsOnlyOnce("a1")
        }

        @Test
        fun `waits for the refill threshold of finished keys before querying again after a full batch`() {
            val keys = ('A'..'H').map { it.toString() }
            keys.forEach { add(it, it.lowercase() + "1") }
            val blocked = keys.associateWith { block(it.lowercase() + "1") }

            // The trigger delay is one minute, so each query below is caused by finished keys, not by the timeout.
            val run = startDispatch(dispatcher(batchSize = 8))
            awaitStarted(*keys.map { it.lowercase() + "1" }.toTypedArray())

            blocked.getValue("A").countDown()
            await().atMost(TIMEOUT_SECONDS, SECONDS).until { processed.contains("a1") }
            await().during(Duration.ofMillis(200)).atMost(TIMEOUT_SECONDS, SECONDS).until { queries.size == 1 }

            blocked.getValue("B").countDown()
            await().atMost(TIMEOUT_SECONDS, SECONDS).until { queries.size == 2 }

            blocked.values.forEach { it.countDown() }
            run.get(TIMEOUT_SECONDS, SECONDS)
            assertThat(processed).hasSize(8)
            verify(exactly = 1) { trigger.onPollResult(8, 8) }
            verify(exactly = 1) { trigger.onPollResult(0, 2) }
        }

        @Test
        fun `queries again after the wait timeout when only a slow key remains`() {
            triggerDelay = Duration.ofMillis(50)
            add("A", "a1")
            val slow = block("a1")

            val run = startDispatch(dispatcher(batchSize = 8))
            awaitStarted("a1")
            add("B", "b1")

            await().atMost(TIMEOUT_SECONDS, SECONDS).until { processed.contains("b1") }
            assertThat(processed).doesNotContain("a1")

            slow.countDown()
            run.get(TIMEOUT_SECONDS, SECONDS)
            assertThat(processed).hasSize(2)
        }

        @Test
        fun `queries at most once per wait timeout while only a slow key is in flight`() {
            triggerDelay = Duration.ofMillis(50)
            add("A", "a1")
            val slow = block("a1")

            val run = startDispatch(dispatcher(batchSize = 4))
            awaitStarted("a1")
            Thread.sleep(500)
            val queriesWhileWaiting = queries.size

            slow.countDown()
            run.get(TIMEOUT_SECONDS, SECONDS)
            // One query per 50 ms over 500 ms is about 10; a busy loop would query far more often.
            assertThat(queriesWhileWaiting).isBetween(2, 15)
        }

        @Test
        fun `skips the key query while every slot is held after the wait timeout`() {
            triggerDelay = Duration.ofMillis(20)
            add("A", "a1")
            add("B", "b1")
            val slowA = block("a1")
            val slowB = block("b1")

            val run = startDispatch(dispatcher(batchSize = 2))
            awaitStarted("a1", "b1")
            add("C", "c1")

            // Several wait timeouts pass without a free slot.
            await().during(Duration.ofMillis(200)).atMost(TIMEOUT_SECONDS, SECONDS).until { queries.size == 1 }
            verify(exactly = 1) { trigger.onPollResult(any(), any()) }

            slowA.countDown()
            await().atMost(TIMEOUT_SECONDS, SECONDS).until { processed.contains("c1") }
            assertThat(processed).doesNotContain("b1")

            slowB.countDown()
            run.get(TIMEOUT_SECONDS, SECONDS)
            assertThat(processed).containsExactlyInAnyOrder("a1", "b1", "c1")
        }
    }

    @Nested
    @DisplayName("Key selection")
    inner class KeySelection {
        @Test
        fun `skips keys in flight and selects at most one key per free slot`() {
            add("A", "a1")
            val slow = block("a1")
            add("B", "b1")
            add("C", "c1")
            add("D", "d1")

            val run = startDispatch(dispatcher(batchSize = 2))

            await().atMost(TIMEOUT_SECONDS, SECONDS).untilAsserted {
                assertThat(processed).containsExactly("b1", "c1", "d1")
                assertThat(queries).hasSize(4)
            }
            slow.countDown()

            run.get(TIMEOUT_SECONDS, SECONDS)
            assertThat(processed).hasSize(4)
            assertThat(queries).containsExactly(
                listOf("A", "B"),
                listOf("A", "C"),
                listOf("A", "D"),
                listOf("A"),
            )
            verify(exactly = 4) { trigger.onPollResult(any(), any()) }
            verify(exactly = 1) { trigger.onPollResult(2, 2) }
            verify(exactly = 2) { trigger.onPollResult(1, 1) }
            verify(exactly = 1) { trigger.onPollResult(0, 1) }
            verify(exactly = 1) { invoker.process(match { it.id == "a1" }) }
        }

        @Test
        fun `keeps a key in flight until its completion is acknowledged`() {
            add("A", "a1")
            add("B", "b1")
            add("C", "c1")
            // Completion batching: the worker of A waits for its acknowledged commit, and A's record stays due until then.
            val acknowledgement = block("a1")

            val run = startDispatch(dispatcher(batchSize = 2))

            await().atMost(TIMEOUT_SECONDS, SECONDS).untilAsserted {
                assertThat(processed).containsExactly("b1", "c1")
                assertThat(queries.last()).containsExactly("A")
            }
            acknowledgement.countDown()

            run.get(TIMEOUT_SECONDS, SECONDS)
            assertThat(processed).hasSize(3)
            verify(exactly = 1) { invoker.process(match { it.id == "a1" }) }
        }

        @ParameterizedTest
        @ValueSource(booleans = [true, false])
        fun `applies the failure mode to key queries and record processing`(strict: Boolean) {
            add("A", "a1", "a2")
            outcomes["a1"] = { OutboxRecordProcessingOutcome.FAILED }

            dispatcher(batchSize = 4, strict = strict).dispatch(setOf(1)) { false }

            verify { repository.findRecordKeysInPartitions(setOf(1), 4, strict) }
            if (strict) {
                assertThat(processed).containsExactly("a1")
            } else {
                assertThat(processed).containsExactly("a1", "a2")
            }
        }

        @ParameterizedTest
        @ValueSource(longs = [1, 2, 3, 4, 5])
        fun `keeps per-key order and the in-flight limit under random load`(seed: Long) {
            val random = Random(seed)
            triggerDelay = Duration.ofMillis(5)
            processingDelay = { record -> if (record.id.hashCode() % 7 == 0) 30L else 1L }
            val keys = (1..30).map { "K$it" }
            val expected = LinkedHashMap<String, MutableList<String>>()
            var sequence = 0

            fun addNext(key: String) =
                synchronized(expected) {
                    val id = "$key-${sequence++}"
                    expected.getOrPut(key) { mutableListOf() } += id
                    add(key, id)
                }
            repeat(60) { addNext(keys[random.nextInt(keys.size)]) }
            // Some records schedule a successor of a random key while the run is in progress.
            synchronized(expected) { expected.values.flatten() }
                .filter { random.nextInt(4) == 0 }
                .forEach { id ->
                    val key = keys[random.nextInt(keys.size)]
                    afterProcessing[id] = { addNext(key) }
                }
            val dispatcher = dispatcher(batchSize = 4)

            // Successors may arrive after a run ended; later runs pick them up, as the scheduler would.
            await().atMost(20, SECONDS).untilAsserted {
                dispatcher.dispatch(setOf(1)) { false }
                assertThat(synchronized(pending) { pending.values.all { it.isEmpty() } }).isTrue()
            }

            assertThat(processed).doesNotHaveDuplicates()
            synchronized(expected) {
                assertThat(processed).hasSize(expected.values.sumOf { it.size })
                expected.forEach { (key, ids) ->
                    assertThat(
                        processed.filter { it.startsWith("$key-") },
                    ).describedAs("order of %s", key).isEqualTo(ids)
                }
            }
        }
    }

    /**
     * Every test keeps key A in flight, causes the stop, and then offers key C, which a run that kept
     * dispatching would select. The run must not select C and must not return before A has finished.
     */
    @Nested
    @DisplayName("Stopping")
    inner class Stopping {
        @Test
        fun `stops after a failed key query and reports it as nothing found`() {
            triggerDelay = Duration.ofMillis(20)
            add("A", "a1")
            val slow = block("a1")
            queryFailure = { if (it == 2) IllegalStateException("query failed") else null }

            val run = startDispatch(dispatcher(batchSize = 2))
            awaitStarted("a1")
            await().atMost(TIMEOUT_SECONDS, SECONDS).until { queries.size == 2 }
            add("C", "c1")

            assertDrainsWithoutDispatching(run, slow, expectedQueries = 2)
            verify(exactly = 1) { trigger.onPollResult(1, 2) }
            verify(exactly = 1) { trigger.onPollResult(0, 1) }
        }

        @Test
        fun `stops after a failed record load`() {
            triggerDelay = Duration.ofMillis(20)
            add("A", "a1")
            val slow = block("a1")
            pageLoadFailure = { if (it == 2) IllegalStateException("load failed") else null }

            val run = startDispatch(dispatcher(batchSize = 2))
            awaitStarted("a1")
            // Queries that find only A load nothing, so the first query that selects B makes the second load.
            add("B", "b1")
            await().atMost(TIMEOUT_SECONDS, SECONDS).until { queries.any { "B" in it } }
            add("C", "c1")

            assertDrainsWithoutDispatching(run, slow, expectedQueries = queries.size)
            assertThat(processed).doesNotContain("b1")
        }

        @Test
        fun `stops after a rejected submission and releases its slot`() {
            add("A", "a1")
            add("B", "b1")
            val slow = block("a1")
            val submissions = AtomicInteger()
            val executor =
                TaskExecutor { task ->
                    if (submissions.incrementAndGet() > 1) throw RejectedExecutionException("full")
                    workers.execute(task)
                }

            // A slot kept by the rejected key would make the final drain wait forever.
            val run = startDispatch(dispatcher(batchSize = 4, executor = executor))
            awaitStarted("a1")
            add("C", "c1")

            assertDrainsWithoutDispatching(run, slow, expectedQueries = 1)
            assertThat(processed).doesNotContain("b1")
        }

        @Test
        fun `stops after a key fails unexpectedly`() {
            add("A", "a1")
            add("B", "b1")
            val slow = block("a1")
            every { invoker.process(match { it.id == "b1" }) } throws RuntimeException("boom")

            // A full batch: the dispatcher selects C only after B has released its slot.
            val run = startDispatch(dispatcher(batchSize = 2))
            awaitStarted("a1")
            add("C", "c1")

            assertDrainsWithoutDispatching(run, slow, expectedQueries = 1)
        }

        @Test
        fun `stops after a compatibility failure and activates the cooldown`() {
            add("A", "a1")
            add("B", "b1")
            val slow = block("a1")
            outcomes["b1"] = {
                throw OutboxHandlerNotFoundException(recordId = "b1", recordKey = "B", handlerId = "missing")
            }
            val dispatcher = dispatcher(batchSize = 2)

            val run = startDispatch(dispatcher)
            awaitStarted("a1", "b1")
            await().atMost(TIMEOUT_SECONDS, SECONDS).until { dispatcher.isCompatibilityCooldownActive() }
            add("C", "c1")

            assertDrainsWithoutDispatching(run, slow, expectedQueries = 1)
        }

        @Test
        fun `stops when the stop signal is raised during a wait`() {
            val stopping = AtomicBoolean(false)
            val (run, slow) = startFullBatchWaitingForAFreeSlot { stopping.get() }

            stopping.set(true)
            add("C", "c1")
            releaseSecondKey()

            assertDrainsWithoutDispatching(run, slow, expectedQueries = 1)
        }

        @Test
        fun `stops when the dispatching thread is interrupted and keeps the interrupt`() {
            val (run, slow) = startFullBatchWaitingForAFreeSlot { false }

            dispatchingThread.get().interrupt()
            add("C", "c1")
            releaseSecondKey()

            assertDrainsWithoutDispatching(run, slow, expectedQueries = 1)
            assertThat(interruptedAfterDispatch.get()).isTrue()
        }

        @Test
        fun `stops when the trigger returns no next execution`() {
            every { trigger.nextExecution(any()) } returns null
            add("A", "a1")
            val slow = block("a1")

            val run = startDispatch(dispatcher(batchSize = 1))
            awaitStarted("a1")
            add("C", "c1")

            assertDrainsWithoutDispatching(run, slow, expectedQueries = 1)
        }

        /** Starts a full batch of A and B, both blocked, so the dispatcher waits for a free slot. */
        private fun startFullBatchWaitingForAFreeSlot(shouldStop: () -> Boolean): Pair<Future<*>, CountDownLatch> {
            add("A", "a1")
            add("B", "b1")
            val slow = block("a1")
            block("b1")
            val run = startDispatch(dispatcher(batchSize = 2), shouldStop)
            awaitStarted("a1", "b1")
            return run to slow
        }

        /** Frees a slot, which ends the dispatcher's wait for a free slot. */
        private fun releaseSecondKey() {
            blockers.getValue("b1").countDown()
            await().atMost(TIMEOUT_SECONDS, SECONDS).until { processed.contains("b1") }
        }

        private fun assertDrainsWithoutDispatching(
            run: Future<*>,
            slow: CountDownLatch,
            expectedQueries: Int,
        ) {
            await().during(Duration.ofMillis(100)).atMost(TIMEOUT_SECONDS, SECONDS).until { !run.isDone }

            slow.countDown()
            run.get(TIMEOUT_SECONDS, SECONDS)

            assertThat(queries).hasSize(expectedQueries)
            assertThat(processed).contains("a1").doesNotContain("c1")
        }
    }

    @Nested
    inner class WaitTimeout {
        @Test
        fun `uses the fixed polling interval`() {
            val dispatcher = dispatcher(trigger = FixedPollingTrigger(Duration.ofSeconds(2), clock))

            assertThat(dispatcher.waitTimeout()).isEqualTo(Duration.ofSeconds(2))
        }

        @Test
        fun `uses the current adaptive delay`() {
            val adaptive = AdaptivePollingTrigger(Duration.ofSeconds(1), Duration.ofSeconds(8), clock)
            val dispatcher = dispatcher(trigger = adaptive)

            assertThat(dispatcher.waitTimeout()).isEqualTo(Duration.ofSeconds(1))
            adaptive.onPollResult(found = 0, requested = 4)
            assertThat(dispatcher.waitTimeout()).isEqualTo(Duration.ofSeconds(2))
        }

        @Test
        fun `is absent when the trigger returns no next execution`() {
            every { trigger.nextExecution(any()) } returns null

            assertThat(dispatcher().waitTimeout()).isNull()
        }

        @ParameterizedTest
        @ValueSource(longs = [500_000, 0, -5_000_000_000])
        fun `is raised to one millisecond`(delayNanos: Long) {
            every { trigger.nextExecution(any()) } returns now.plusNanos(delayNanos)

            assertThat(dispatcher().waitTimeout()).isEqualTo(Duration.ofMillis(1))
        }
    }

    private fun dispatcher(
        batchSize: Int = 10,
        strict: Boolean = true,
        executor: TaskExecutor = TaskExecutor { workers.execute(it) },
        trigger: OutboxPollingTrigger = this.trigger,
    ): ContinuousKeyDispatcher {
        this.batchSize = batchSize
        val settings =
            OutboxRuntimeSettings(
                polling = OutboxRuntimeSettings.Polling(batchSize = batchSize),
                processing = OutboxRuntimeSettings.Processing(stopOnFirstFailure = strict),
            )
        return ContinuousKeyDispatcher(
            recordRepository = repository,
            keyProcessor = OutboxRecordKeyProcessor(invoker, settings.processing, clock),
            taskExecutor = TaskExecutorAdapter(executor),
            settings = settings,
            trigger = trigger,
            clock = clock,
        )
    }

    private fun startDispatch(
        dispatcher: ContinuousKeyDispatcher,
        shouldStop: () -> Boolean = { false },
    ): Future<*> =
        dispatchingExecutor.submit {
            dispatchingThread.set(Thread.currentThread())
            try {
                dispatcher.dispatch(setOf(1), shouldStop)
            } finally {
                interruptedAfterDispatch.set(Thread.interrupted())
            }
        }

    private fun add(
        key: String,
        vararg ids: String,
    ) {
        synchronized(pending) {
            val records = pending.getOrPut(key) { mutableListOf() }
            ids.forEach { id ->
                records += OutboxRecordTestFactory.outboxRecord(id = id, recordKey = key, nextRetryAt = now)
            }
        }
    }

    private fun block(id: String): CountDownLatch = CountDownLatch(1).also { blockers[id] = it }

    private fun awaitStarted(vararg ids: String) {
        ids.forEach { id ->
            assertThat(started.computeIfAbsent(id) { CountDownLatch(1) }.await(TIMEOUT_SECONDS, SECONDS))
                .describedAs("record %s started", id)
                .isTrue()
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 5L
    }
}
