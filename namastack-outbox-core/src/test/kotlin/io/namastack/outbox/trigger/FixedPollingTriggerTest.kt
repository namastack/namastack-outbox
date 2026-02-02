package io.namastack.outbox.trigger

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.scheduling.TriggerContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class FixedPollingTriggerTest {
    private val instant = Instant.parse("2026-01-01T12:00:00Z")
    private val clock: Clock = Clock.fixed(instant, ZoneId.of("UTC"))

    private val triggerContext = mockk<TriggerContext>(relaxed = true)

    @Test
    fun `nextExecution uses current time when no last completion`() {
        val delay = Duration.ofSeconds(3)
        val trigger = FixedPollingTrigger(delay = delay, clock = clock)

        every { triggerContext.lastCompletion() } returns null

        val next = trigger.nextExecution(triggerContext)
        assertThat(next).isEqualTo(instant.plus(delay))
    }

    @Test
    fun `nextExecution adds fixed delay to last completion`() {
        val delay = Duration.ofMillis(2500)
        val trigger = FixedPollingTrigger(delay = delay, clock = clock)

        every { triggerContext.lastCompletion() } returns instant

        val next = trigger.nextExecution(triggerContext)
        assertThat(next).isEqualTo(instant.plus(delay))
    }

    @Test
    fun `nextExecution is consistent across multiple invocations`() {
        val delay = Duration.ofMillis(1000)
        val trigger = FixedPollingTrigger(delay = delay, clock = clock)

        every { triggerContext.lastCompletion() } returns instant

        val next1 = trigger.nextExecution(triggerContext)
        val next2 = trigger.nextExecution(triggerContext)
        assertThat(next1).isEqualTo(instant.plus(delay))
        assertThat(next2).isEqualTo(instant.plus(delay))
    }
}
