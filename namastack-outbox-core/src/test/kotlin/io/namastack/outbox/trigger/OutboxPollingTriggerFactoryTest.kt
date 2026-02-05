package io.namastack.outbox.trigger

import io.mockk.every
import io.mockk.mockk
import io.namastack.outbox.OutboxProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.scheduling.TriggerContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

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
                    fixedInterval = 3000,
                )
            every { triggerContext.lastCompletion() } returns instant

            val trigger = OutboxPollingTriggerFactory.create(prop, clock)

            assertThat(trigger).isInstanceOf(FixedPollingTrigger::class.java)
            val next = trigger.nextExecution(triggerContext)
            assertThat(next).isEqualTo(instant.plusMillis(3000))
        }

        @Test
        fun `fixed trigger using deprecated pollInterval`() {
            val prop =
                properties(
                    pollInterval = 3000,
                    pollingTrigger = "fixed",
                )
            every { triggerContext.lastCompletion() } returns instant

            val trigger = OutboxPollingTriggerFactory.create(prop, clock)
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
                adaptiveMinInterval = 2000,
                adaptiveMaxInterval = 4000,
            )
        every { triggerContext.lastCompletion() } returns instant

        val trigger = OutboxPollingTriggerFactory.create(prop, clock)
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

        assertThatThrownBy { OutboxPollingTriggerFactory.create(prop, clock) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Unsupported polling-trigger")
    }

    private fun properties(
        pollInterval: Long? = null,
        batchSize: Int? = null,
        pollingBatchSize: Int? = null,
        pollingTrigger: String? = null,
        fixedInterval: Long? = null,
        adaptiveMinInterval: Long? = null,
        adaptiveMaxInterval: Long? = null,
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
