package io.namastack.outbox.dispatch

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import io.namastack.outbox.LazyOutboxRecordPage
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxRecordTestFactory
import io.namastack.outbox.instrumentation.OutboxRecordProcessingOutcome
import io.namastack.outbox.processor.OutboxRecordProcessorChainInvoker
import io.namastack.outbox.runtime.OutboxRuntimeSettings
import io.namastack.outbox.trigger.OutboxPollingTrigger
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.core.task.support.TaskExecutorAdapter
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class BatchKeyDispatcherTest {
    private val now = Instant.parse("2026-01-01T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private val repository = mockk<OutboxRecordRepository>()
    private val invoker = mockk<OutboxRecordProcessorChainInvoker>()
    private val trigger = mockk<OutboxPollingTrigger>(relaxed = true)

    private val pages = mutableMapOf<String, List<OutboxRecord<*>>>()

    init {
        every { repository.findIncompleteRecordPages(any()) } answers {
            firstArg<Set<String>>().associateWith { LazyOutboxRecordPage(pages[it].orEmpty()) { record -> record } }
        }
        every { invoker.process(any()) } returns OutboxRecordProcessingOutcome.COMPLETED
    }

    @Nested
    @DisplayName("Processing")
    inner class Processing {
        @ParameterizedTest
        @ValueSource(booleans = [true, false])
        fun `queries one batch with the batch size and failure mode`(strict: Boolean) {
            selectKeys()

            dispatcher(batchSize = 7, strict = strict).dispatch(setOf(1, 2)) { false }

            verify(exactly = 1) { repository.findRecordKeysInPartitions(setOf(1, 2), 7, strict) }
        }

        @Test
        fun `processes the whole batch even when a stop is requested`() {
            val a = add("A")
            val b = add("B")
            selectKeys("A", "B")

            dispatcher(batchSize = 10).dispatch(setOf(1)) { true }

            verify(exactly = 1) { invoker.process(a) }
            verify(exactly = 1) { invoker.process(b) }
            verify(exactly = 1) { repository.findRecordKeysInPartitions(any(), any(), any()) }
        }

        @Test
        fun `a key that fails unexpectedly does not stop the other keys of the batch`() {
            val a = add("A")
            val b = add("B")
            selectKeys("A", "B")
            every { invoker.process(a) } throws RuntimeException("boom")

            dispatcher(batchSize = 10).dispatch(setOf(1)) { false }

            verify(exactly = 1) { invoker.process(b) }
        }
    }

    @Nested
    @DisplayName("Poll results")
    inner class PollResults {
        @Test
        fun `reports the selected keys against the batch size once before processing`() {
            val record = add("A")
            selectKeys("A", "B")

            dispatcher(batchSize = 10).dispatch(setOf(1)) { false }

            verify(exactly = 1) { trigger.onPollResult(any(), any()) }
            verifyOrder {
                trigger.onPollResult(2, 10)
                invoker.process(record)
            }
        }

        @Test
        fun `reports an empty run and loads no records`() {
            selectKeys()

            dispatcher(batchSize = 10).dispatch(setOf(1)) { false }

            verify(exactly = 1) { trigger.onPollResult(0, 10) }
            verify(exactly = 0) { repository.findIncompleteRecordPages(any()) }
        }

        @Test
        fun `reports a failed key query as nothing found and ends the run`() {
            every { repository.findRecordKeysInPartitions(any(), any(), any()) } throws RuntimeException("unavailable")

            dispatcher(batchSize = 10).dispatch(setOf(1)) { false }

            verify(exactly = 1) { trigger.onPollResult(any(), any()) }
            verify(exactly = 1) { trigger.onPollResult(0, 10) }
            verify(exactly = 0) { repository.findIncompleteRecordPages(any()) }
        }

        @Test
        fun `reports the selected keys once when the record load fails`() {
            selectKeys("A")
            every { repository.findIncompleteRecordPages(any()) } throws RuntimeException("unavailable")

            assertThatThrownBy { dispatcher(batchSize = 10).dispatch(setOf(1)) { false } }
                .hasMessage("unavailable")

            verify(exactly = 1) { trigger.onPollResult(any(), any()) }
            verify(exactly = 1) { trigger.onPollResult(1, 10) }
        }
    }

    private fun dispatcher(
        batchSize: Int,
        strict: Boolean = true,
    ): BatchKeyDispatcher {
        val settings =
            OutboxRuntimeSettings(
                polling = OutboxRuntimeSettings.Polling(batchSize = batchSize),
                processing = OutboxRuntimeSettings.Processing(stopOnFirstFailure = strict),
            )
        return BatchKeyDispatcher(
            recordRepository = repository,
            keyProcessor = OutboxRecordKeyProcessor(invoker, settings.processing, clock),
            taskExecutor = TaskExecutorAdapter(SyncTaskExecutor()),
            settings = settings,
            trigger = trigger,
        )
    }

    private fun selectKeys(vararg keys: String) {
        every { repository.findRecordKeysInPartitions(any(), any(), any()) } returns keys.toList()
    }

    private fun add(key: String): OutboxRecord<*> =
        OutboxRecordTestFactory
            .outboxRecord(recordKey = key, nextRetryAt = now.minusSeconds(1))
            .also { pages[key] = pages[key].orEmpty() + it }
}
