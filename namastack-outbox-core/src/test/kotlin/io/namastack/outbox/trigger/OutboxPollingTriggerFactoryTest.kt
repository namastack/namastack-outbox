package io.namastack.outbox.trigger

import io.mockk.every
import io.mockk.mockk
import io.namastack.outbox.OutboxProperties
import io.namastack.outbox.runtime.toRuntimeSettings
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.scheduling.TriggerContext
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger

class OutboxPollingTriggerFactoryTest {
    private val instant = Instant.parse("2026-01-01T12:00:00Z")
    private val clock: Clock = Clock.fixed(instant, ZoneId.of("UTC"))

    private val triggerContext = mockk<TriggerContext>(relaxed = true)

    @Nested
    @DisplayName("fixed trigger")
    inner class FixedTriggerTests {
        @Test
        fun `fixed trigger`() {
            val prop =
                properties(
                    pollingTrigger = "fixed",
                    fixedInterval = Duration.ofSeconds(3),
                )
            every { triggerContext.lastCompletion() } returns instant

            val trigger = OutboxPollingTriggerFactory.create(prop.toRuntimeSettings().polling, clock)

            assertThat(trigger).isInstanceOf(FixedPollingTrigger::class.java)
            val next = trigger.nextExecution(triggerContext)
            assertThat(next).isEqualTo(instant.plusMillis(3000))
        }

        @Test
        fun `fixed trigger using deprecated pollInterval`() {
            val prop =
                properties(
                    pollInterval = Duration.ofSeconds(3),
                    pollingTrigger = "fixed",
                )
            every { triggerContext.lastCompletion() } returns instant

            val trigger = OutboxPollingTriggerFactory.create(prop.toRuntimeSettings().polling, clock)
            assertThat(trigger).isInstanceOf(FixedPollingTrigger::class.java)

            val next = trigger.nextExecution(triggerContext)
            assertThat(next).isEqualTo(instant.plusMillis(3000))
        }
    }

    @Test
    fun `adaptive trigger`() {
        val prop =
            properties(
                pollingBatchSize = 20,
                pollingTrigger = "adaptive",
                adaptiveMinInterval = Duration.ofSeconds(2),
                adaptiveMaxInterval = Duration.ofSeconds(4),
            )
        every { triggerContext.lastCompletion() } returns instant

        val trigger = OutboxPollingTriggerFactory.create(prop.toRuntimeSettings().polling, clock)
        assertThat(trigger).isInstanceOf(AdaptivePollingTrigger::class.java)

        // min boundary
        val next = trigger.nextExecution(triggerContext)
        assertThat(next).isEqualTo(instant.plusMillis(2000))

        // max boundary
        trigger.onTaskComplete(5)
        trigger.onTaskComplete(5)
        val next2 = trigger.nextExecution(triggerContext)
        assertThat(next2).isEqualTo(instant.plusMillis(4000))
    }

    @Test
    fun `throws on unsupported polling trigger`() {
        val prop = properties(pollingTrigger = "unknown")

        assertThatThrownBy { OutboxPollingTriggerFactory.create(prop.toRuntimeSettings().polling, clock) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Unsupported polling-trigger")
    }

    @ParameterizedTest
    @ValueSource(strings = ["fixed", "adaptive"])
    fun `drains full batches then resumes configured polling`(strategy: String) {
        val properties = properties(pollingBatchSize = 10, pollingTrigger = strategy)
        properties.polling.drainOnFullBatch = true
        every { triggerContext.lastCompletion() } returns instant
        val trigger = OutboxPollingTriggerFactory.create(properties.toRuntimeSettings().polling, clock)

        assertThat(trigger.nextExecution(triggerContext)).isAfter(instant)
        repeat(3) {
            trigger.onTaskComplete(10)
            assertThat(trigger.nextExecution(triggerContext)).isEqualTo(instant)
        }
        trigger.onTaskComplete(9)
        assertThat(trigger.nextExecution(triggerContext)).isAfter(instant)
        trigger.onTaskComplete(10)
        trigger.onTaskComplete(0)
        assertThat(trigger.nextExecution(triggerContext)).isAfter(instant)
    }

    @Test
    fun `full batch keeps the configured delay by default`() {
        val trigger = OutboxPollingTriggerFactory.create(properties().toRuntimeSettings().polling, clock)
        every { triggerContext.lastCompletion() } returns instant
        trigger.onTaskComplete(10)
        assertThat(trigger.nextExecution(triggerContext)).isEqualTo(instant.plusSeconds(2))
    }

    @Test
    fun `immediate batches return to a single scheduler so another due task can run`() {
        val properties = properties(pollingBatchSize = 1, fixedInterval = Duration.ofMinutes(5))
        properties.polling.drainOnFullBatch = true
        val trigger = OutboxPollingTriggerFactory.create(properties.toRuntimeSettings().polling, Clock.systemUTC())
        trigger.onTaskComplete(1)
        val scheduler = ThreadPoolTaskScheduler()
        scheduler.poolSize = 1
        scheduler.initialize()
        val firstBatchStarted = CountDownLatch(1)
        val finishFirstBatch = CountDownLatch(1)
        val thirdBatchFinished = CountDownLatch(1)
        val batchCount = AtomicInteger()
        val otherTaskRanAfterBatch = AtomicInteger(-1)
        try {
            scheduler.schedule(
                Runnable {
                    val batch = batchCount.incrementAndGet()
                    if (batch == 1) {
                        firstBatchStarted.countDown()
                        check(finishFirstBatch.await(5, SECONDS))
                    }
                    trigger.onTaskComplete(if (batch < 3) 1 else 0)
                    if (batch == 3) thirdBatchFinished.countDown()
                },
                trigger,
            )
            assertThat(firstBatchStarted.await(2, SECONDS)).isTrue()
            scheduler.schedule(Runnable { otherTaskRanAfterBatch.set(batchCount.get()) }, Instant.now())
            finishFirstBatch.countDown()
            assertThat(thirdBatchFinished.await(2, SECONDS)).isTrue()
            assertThat(otherTaskRanAfterBatch.get()).isEqualTo(1)
            assertThat(batchCount.get()).isEqualTo(3)
        } finally {
            finishFirstBatch.countDown()
            scheduler.shutdown()
        }
    }

    private fun properties(
        pollInterval: Duration? = null,
        batchSize: Int? = null,
        pollingBatchSize: Int? = null,
        pollingTrigger: String? = null,
        fixedInterval: Duration? = null,
        adaptiveMinInterval: Duration? = null,
        adaptiveMaxInterval: Duration? = null,
    ): OutboxProperties {
        val properties = OutboxProperties()

        pollInterval?.let { properties.pollInterval = it }
        batchSize?.let { properties.batchSize = it }
        pollingBatchSize?.let { properties.polling.batchSize = it }
        pollingTrigger?.let { properties.polling.trigger = it }
        fixedInterval?.let { properties.polling.fixed.interval = it }
        adaptiveMinInterval?.let { properties.polling.adaptive.minInterval = it }
        adaptiveMaxInterval?.let { properties.polling.adaptive.maxInterval = it }

        return properties
    }
}
