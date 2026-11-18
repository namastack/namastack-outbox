package io.namastack.outbox.dispatch

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import io.namastack.outbox.LazyOutboxRecordPage
import io.namastack.outbox.OutboxHandlerNotFoundException
import io.namastack.outbox.OutboxPayloadTypeNotFoundException
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordTestFactory
import io.namastack.outbox.instrumentation.OutboxRecordProcessingOutcome
import io.namastack.outbox.processor.OutboxRecordProcessorChainInvoker
import io.namastack.outbox.runtime.OutboxRuntimeSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference

class OutboxRecordKeyProcessorTest {
    private val now = Instant.parse("2024-01-01T10:00:00Z")
    private val clock = MutableClock(now)
    private val invoker = mockk<OutboxRecordProcessorChainInvoker>()

    @Nested
    inner class RecordProcessing {
        @Test
        fun `processes nothing for an empty page`() {
            assertThat(processor().process(KEY, page())).isTrue()

            verify(exactly = 0) { invoker.process(any()) }
        }

        @Test
        fun `processes the records of a key in page order`() {
            val first = ready()
            val second = ready()
            val third = ready()
            every { invoker.process(any()) } returns OutboxRecordProcessingOutcome.COMPLETED

            assertThat(processor().process(KEY, page(first, second, third))).isTrue()

            verifyOrder {
                invoker.process(first)
                invoker.process(second)
                invoker.process(third)
            }
        }

        @ParameterizedTest
        @ValueSource(booleans = [true, false])
        fun `applies the failure mode to a record that is not ready for retry`(strict: Boolean) {
            val notReady = OutboxRecordTestFactory.outboxRecord(recordKey = KEY, nextRetryAt = now.plusSeconds(5))
            val successor = ready()
            every { invoker.process(any()) } returns OutboxRecordProcessingOutcome.COMPLETED

            assertThat(processor(strict).process(KEY, page(notReady, successor))).isTrue()

            verify(exactly = 0) { invoker.process(notReady) }
            verify(exactly = if (strict) 0 else 1) { invoker.process(successor) }
        }

        @ParameterizedTest
        @ValueSource(booleans = [true, false])
        fun `applies the failure mode to a record that is not completed`(strict: Boolean) {
            val failing = ready()
            val successor = ready()
            every { invoker.process(failing) } returns OutboxRecordProcessingOutcome.RETRY_SCHEDULED
            every { invoker.process(successor) } returns OutboxRecordProcessingOutcome.COMPLETED

            // An unsuccessful outcome is regular processing, not an unexpected failure of the key.
            assertThat(processor(strict).process(KEY, page(failing, successor))).isTrue()

            verify(exactly = 1) { invoker.process(failing) }
            verify(exactly = if (strict) 0 else 1) { invoker.process(successor) }
        }

        @Test
        fun `reports an unexpected exception without activating a cooldown`() {
            val record = ready()
            val successor = ready()
            every { invoker.process(record) } throws RuntimeException("boom")
            val processor = processor(strict = false)

            assertThat(processor.process(KEY, page(record, successor))).isFalse()

            verify(exactly = 0) { invoker.process(successor) }
            assertThat(processor.isCompatibilityCooldownActive()).isFalse()
        }
    }

    @Nested
    inner class CompatibilityCooldown {
        @Test
        fun `is inactive initially`() {
            assertThat(processor().isCompatibilityCooldownActive()).isFalse()
        }

        @Test
        fun `unavailable handler activates the cooldown and stops the key`() {
            val record = ready()
            val successor = ready()
            every { invoker.process(record) } throws
                OutboxHandlerNotFoundException(recordId = record.id, recordKey = KEY, handlerId = record.handlerId)
            val processor = processor(strict = false)

            assertThat(processor.process(KEY, page(record, successor))).isFalse()

            verify(exactly = 0) { invoker.process(successor) }
            assertThat(processor.isCompatibilityCooldownActive()).isTrue()
        }

        @Test
        fun `unavailable payload type activates the cooldown without invoking the chain`() {
            val failure =
                OutboxPayloadTypeNotFoundException(
                    recordId = "record-id",
                    recordKey = KEY,
                    payloadType = "example.UnavailablePayload",
                    handlerId = "handler-id",
                    cause = ClassNotFoundException("example.UnavailablePayload"),
                )
            val undecodable = LazyOutboxRecordPage(listOf(KEY)) { throw failure }
            val processor = processor()

            assertThat(processor.process(KEY, undecodable)).isFalse()

            verify(exactly = 0) { invoker.process(any()) }
            assertThat(processor.isCompatibilityCooldownActive()).isTrue()
        }

        @Test
        fun `expires after thirty seconds`() {
            val processor = processor()
            activateCooldown(processor)

            clock.advanceBy(Duration.ofSeconds(30).minusMillis(1))
            assertThat(processor.isCompatibilityCooldownActive()).isTrue()

            clock.advanceBy(Duration.ofMillis(1))
            assertThat(processor.isCompatibilityCooldownActive()).isFalse()
            assertThat(processor.isCompatibilityCooldownActive()).isFalse()
        }

        @Test
        fun `a later compatibility failure extends the cooldown`() {
            val processor = processor()
            activateCooldown(processor)

            clock.advanceBy(Duration.ofSeconds(20))
            activateCooldown(processor)

            clock.advanceBy(Duration.ofSeconds(20))
            assertThat(processor.isCompatibilityCooldownActive()).isTrue()

            clock.advanceBy(Duration.ofSeconds(10))
            assertThat(processor.isCompatibilityCooldownActive()).isFalse()
        }

        private fun activateCooldown(processor: OutboxRecordKeyProcessor) {
            val record = ready()
            every { invoker.process(record) } throws
                OutboxHandlerNotFoundException(recordId = record.id, recordKey = KEY, handlerId = record.handlerId)
            processor.process(KEY, page(record))
        }
    }

    private fun processor(strict: Boolean = true): OutboxRecordKeyProcessor =
        OutboxRecordKeyProcessor(invoker, OutboxRuntimeSettings.Processing(stopOnFirstFailure = strict), clock)

    private fun ready(): OutboxRecord<*> =
        OutboxRecordTestFactory.outboxRecord(recordKey = KEY, nextRetryAt = clock.instant().minusSeconds(5))

    private fun page(vararg records: OutboxRecord<*>) = LazyOutboxRecordPage(records.toList()) { it }

    private class MutableClock(
        initialInstant: Instant,
        private val zoneId: ZoneId = ZoneId.of("UTC"),
    ) : Clock() {
        private val currentInstant = AtomicReference(initialInstant)

        override fun getZone(): ZoneId = zoneId

        override fun withZone(zone: ZoneId): Clock = MutableClock(instant(), zone)

        override fun instant(): Instant = currentInstant.get()

        fun advanceBy(duration: Duration) {
            currentInstant.updateAndGet { it.plus(duration) }
        }
    }

    private companion object {
        const val KEY = "record-key"
    }
}
