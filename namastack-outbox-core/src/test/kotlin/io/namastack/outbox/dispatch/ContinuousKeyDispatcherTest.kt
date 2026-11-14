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
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.core.task.TaskExecutor
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

class ContinuousKeyDispatcherTest {
    private val now = Instant.parse("2026-01-01T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private val repository = mockk<OutboxRecordRepository>()
    private val invoker = mockk<OutboxRecordProcessorChainInvoker>()
    private val trigger = mockk<OutboxPollingTrigger>(relaxed = true)

    private val workers = Executors.newCachedThreadPool()
    private val dispatching = Executors.newSingleThreadExecutor()
    private val dispatchingThread = AtomicReference<Thread>()

    /** Fake outbox table: due records per key in processing order. */
    private val pending = LinkedHashMap<String, MutableList<OutboxRecord<*>>>()

    /** Keys with a failed record; strict key queries skip them. */
    private val keysWithFailure = ConcurrentHashMap.newKeySet<String>()

    /** Result of every key query, in query order. */
    private val fetches = CopyOnWriteArrayList<List<String>>()

    /** Ids of records processed, in completion order. */
    private val processed = CopyOnWriteArrayList<String>()

    /** Latches that keep a record's processing (including its completion commit) open. */
    private val blockers = ConcurrentHashMap<String, CountDownLatch>()
    private val started = ConcurrentHashMap<String, CountDownLatch>()
    private val outcomes = ConcurrentHashMap<String, () -> OutboxRecordProcessingOutcome>()
    private val afterProcessing = ConcurrentHashMap<String, () -> Unit>()

    private val activeByKey = ConcurrentHashMap<String, AtomicInteger>()
    private val concurrentKeyProcessing = AtomicBoolean(false)

    private var waitTimeout: Duration = Duration.ofMinutes(1)
    private var fetchFailure: (Int) -> Exception? = { null }
    private var pageLoadFailure: (Int) -> Exception? = { null }

    @BeforeEach
    fun setUp() {
        every { trigger.nextExecution(any()) } answers {
            firstArg<TriggerContext>().lastCompletion()!!.plus(waitTimeout)
        }

        every { repository.findRecordKeysInPartitions(setOf(1), any(), any()) } answers {
            fetchFailure(fetches.size + 1)?.let {
                fetches.add(emptyList())
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
            fetches.add(keys)
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
            try {
                started.computeIfAbsent(record.id) { CountDownLatch(1) }.countDown()
                blockers[record.id]?.await(TIMEOUT_SECONDS, SECONDS)
                val outcome = outcomes[record.id]?.invoke() ?: OutboxRecordProcessingOutcome.COMPLETED
                if (outcome != OutboxRecordProcessingOutcome.COMPLETED) keysWithFailure.add(record.key)
                synchronized(pending) { pending[record.key]?.remove(record) }
                processed.add(record.id)
                afterProcessing[record.id]?.invoke()
                outcome
            } finally {
                active.decrementAndGet()
            }
        }
    }

    @AfterEach
    fun tearDown() {
        blockers.values.forEach { it.countDown() }
        dispatching.shutdownNow()
        workers.shutdownNow()
        assertThat(concurrentKeyProcessing.get()).describedAs("a key was processed twice at the same time").isFalse()
    }

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

        assertThat(run.get(TIMEOUT_SECONDS, SECONDS)).isEqualTo(7)
        assertThat(processed).containsOnlyOnce("a1")
    }

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
            assertThat(fetches).hasSize(4)
        }
        slow.countDown()

        assertThat(run.get(TIMEOUT_SECONDS, SECONDS)).isEqualTo(4)
        assertThat(fetches).containsExactly(
            listOf("A", "B"),
            listOf("A", "C"),
            listOf("A", "D"),
            listOf("A"),
            emptyList(),
        )
        verify(exactly = 1) { trigger.onPollResult(2, 2) }
        verify(exactly = 2) { trigger.onPollResult(1, 1) }
        verify(exactly = 1) { trigger.onPollResult(0, 1) }
        verify(exactly = 1) { trigger.onPollResult(0, 2) }
        verify(exactly = 1) { invoker.process(match { it.id == "a1" }) }
    }

    @Test
    fun `selects a key again in the same run after its task finished`() {
        add("A", "a1")
        afterProcessing["a1"] = { add("A", "a2") }

        val processedKeys = dispatcher(batchSize = 4).dispatch(setOf(1)) { false }

        assertThat(processedKeys).isEqualTo(2)
        assertThat(processed).containsExactly("a1", "a2")
    }

    @Test
    fun `waits for the refill threshold of finished keys before querying again`() {
        add("A", "a1")
        add("B", "b1")
        add("C", "c1")
        val slow = block("a1")
        val first = block("b1")
        val second = block("c1")

        val run = startDispatch(dispatcher(batchSize = 8))
        awaitStarted("a1", "b1", "c1")

        first.countDown()
        await().atMost(TIMEOUT_SECONDS, SECONDS).until { processed.contains("b1") }
        await().during(Duration.ofMillis(200)).atMost(TIMEOUT_SECONDS, SECONDS).until { fetches.size == 1 }

        second.countDown()
        await().atMost(TIMEOUT_SECONDS, SECONDS).until { fetches.size == 2 }

        slow.countDown()
        assertThat(run.get(TIMEOUT_SECONDS, SECONDS)).isEqualTo(3)
    }

    @Test
    fun `queries again after the wait timeout when only a slow key remains`() {
        waitTimeout = Duration.ofMillis(50)
        add("A", "a1")
        val slow = block("a1")

        val run = startDispatch(dispatcher(batchSize = 8))
        awaitStarted("a1")
        add("B", "b1")

        await().atMost(TIMEOUT_SECONDS, SECONDS).until { processed.contains("b1") }
        assertThat(processed).doesNotContain("a1")

        slow.countDown()
        assertThat(run.get(TIMEOUT_SECONDS, SECONDS)).isEqualTo(2)
    }

    @Test
    fun `ends the run when nothing is in flight and the query finds no keys`() {
        val processedKeys = dispatcher(batchSize = 10).dispatch(setOf(1)) { false }

        assertThat(processedKeys).isZero()
        assertThat(fetches).containsExactly(emptyList())
        verify(exactly = 1) { trigger.onPollResult(0, 10) }
        verify(exactly = 0) { repository.findIncompleteRecordPages(any()) }
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
            assertThat(fetches.last()).containsExactly("A")
        }
        acknowledgement.countDown()

        assertThat(run.get(TIMEOUT_SECONDS, SECONDS)).isEqualTo(3)
        verify(exactly = 1) { invoker.process(match { it.id == "a1" }) }
    }

    @Test
    fun `does not select a key again within one wait timeout after an unexpected failure`() {
        add("X", "x1")
        every { invoker.process(match { it.id == "x1" }) } throws RuntimeException("boom")

        val processedKeys = dispatcher(batchSize = 4).dispatch(setOf(1)) { false }

        assertThat(processedKeys).isEqualTo(1)
        assertThat(fetches).containsExactly(listOf("X"), listOf("X"))
        verify(exactly = 1) { invoker.process(any()) }
        verify(exactly = 1) { trigger.onPollResult(1, 4) }
        verify(exactly = 1) { trigger.onPollResult(0, 4) }
    }

    @Test
    fun `rejected submission releases its reservation and drains accepted tasks`() {
        add("A", "a1")
        add("B", "b1")
        val slow = block("a1")
        val submissions = AtomicInteger()
        val executor =
            TaskExecutor { task ->
                if (submissions.incrementAndGet() > 1) throw RejectedExecutionException("full")
                workers.execute(task)
            }

        val run = startDispatch(dispatcher(batchSize = 4, executor = executor))
        awaitStarted("a1")
        await().during(Duration.ofMillis(100)).atMost(TIMEOUT_SECONDS, SECONDS).until { !run.isDone }

        slow.countDown()

        assertThat(run.get(TIMEOUT_SECONDS, SECONDS)).isEqualTo(1)
        assertThat(processed).containsExactly("a1")
        assertThat(fetches).hasSize(1)
    }

    @ParameterizedTest
    @EnumSource(StopCause::class)
    fun `stops dispatching and drains accepted tasks before returning`(cause: StopCause) {
        val stopping = AtomicBoolean(false)
        add("A", "a1")
        val slow = block("a1")
        when (cause) {
            StopCause.FETCH_FAILURE -> {
                add("B", "b1")
                fetchFailure = { if (it == 2) IllegalStateException("query failed") else null }
            }

            StopCause.PAGE_LOAD_FAILURE -> {
                add("B", "b1")
                add("C", "c1")
                pageLoadFailure = { if (it == 2) IllegalStateException("load failed") else null }
            }

            StopCause.COMPATIBILITY_COOLDOWN -> {
                add("B", "b1")
                add("C", "c1")
                outcomes["b1"] = {
                    throw OutboxHandlerNotFoundException(recordId = "b1", recordKey = "B", handlerId = "missing")
                }
            }

            StopCause.INTERRUPT, StopCause.SHUTDOWN -> {
                Unit
            }
        }
        val dispatcher = dispatcher(batchSize = 2)

        val run = startDispatch(dispatcher) { stopping.get() }
        awaitStarted("a1")
        when (cause) {
            StopCause.INTERRUPT -> {
                dispatchingThread.get().interrupt()
            }

            StopCause.SHUTDOWN -> {
                stopping.set(true)
            }

            else -> {
                await().atMost(TIMEOUT_SECONDS, SECONDS).until { fetches.size >= cause.expectedFetches }
            }
        }
        await().during(Duration.ofMillis(100)).atMost(TIMEOUT_SECONDS, SECONDS).until { !run.isDone }

        slow.countDown()
        run.get(TIMEOUT_SECONDS, SECONDS)

        assertThat(fetches).hasSize(cause.expectedFetches)
        assertThat(processed).doesNotContain("c1")
        assertThat(processed).contains("a1")
        if (cause == StopCause.INTERRUPT) {
            assertThat(interruptedAfterDispatch.get()).isTrue()
        }
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

    @Test
    fun `stops dispatching when the trigger returns no next execution`() {
        every { trigger.nextExecution(any()) } returns null
        add("A", "a1")
        add("B", "b1")
        val slow = block("a1")

        val run = startDispatch(dispatcher(batchSize = 1))
        awaitStarted("a1")
        slow.countDown()

        assertThat(run.get(TIMEOUT_SECONDS, SECONDS)).isEqualTo(1)
        assertThat(fetches).hasSize(1)
        assertThat(processed).containsExactly("a1")
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

    private val interruptedAfterDispatch = AtomicBoolean(false)

    private fun dispatcher(
        batchSize: Int = 10,
        strict: Boolean = true,
        executor: TaskExecutor = TaskExecutor { workers.execute(it) },
        trigger: OutboxPollingTrigger = this.trigger,
    ): ContinuousKeyDispatcher {
        val settings =
            OutboxRuntimeSettings(
                polling = OutboxRuntimeSettings.Polling(batchSize = batchSize),
                processing = OutboxRuntimeSettings.Processing(stopOnFirstFailure = strict),
            )
        return ContinuousKeyDispatcher(
            recordRepository = repository,
            keyProcessor = OutboxRecordKeyProcessor(invoker, settings.processing, clock),
            taskExecutor = executor,
            settings = settings,
            trigger = trigger,
            clock = clock,
        )
    }

    private fun startDispatch(
        dispatcher: ContinuousKeyDispatcher,
        isStopping: () -> Boolean = { false },
    ): Future<Int> =
        dispatching.submit<Int> {
            dispatchingThread.set(Thread.currentThread())
            try {
                dispatcher.dispatch(setOf(1), isStopping)
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

    enum class StopCause(
        val expectedFetches: Int,
    ) {
        FETCH_FAILURE(2),
        PAGE_LOAD_FAILURE(2),
        COMPATIBILITY_COOLDOWN(1),
        INTERRUPT(1),
        SHUTDOWN(1),
    }

    private companion object {
        const val TIMEOUT_SECONDS = 5L
    }
}
