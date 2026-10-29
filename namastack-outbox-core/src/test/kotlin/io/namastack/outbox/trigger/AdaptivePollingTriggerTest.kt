package io.namastack.outbox.trigger

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.scheduling.TriggerContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class AdaptivePollingTriggerTest {
    private val instant = Instant.parse("2026-01-01T12:00:00Z")
    private val clock: Clock = Clock.fixed(instant, ZoneId.of("UTC"))

    private val triggerContext = mockk<TriggerContext>(relaxed = true)

    @Test
    fun `nextExecution uses current time when no last completion`() {
        val trigger =
            AdaptivePollingTrigger(
                minDelay = Duration.ofMillis(2000),
                maxDelay = Duration.ofMillis(60000),
                batchSize = 100,
                clock = clock,
            )

        every { triggerContext.lastCompletion() } returns null

        val next = trigger.nextExecution(triggerContext)
        assertThat(next).isEqualTo(instant.plusMillis(2000))
    }

    @Test
    fun `nextExecution adds current adaptive delay to last completion`() {
        val trigger =
            AdaptivePollingTrigger(
                minDelay = Duration.ofMillis(2000),
                maxDelay = Duration.ofMillis(64000),
                batchSize = 100,
                clock = clock,
            )

        every { triggerContext.lastCompletion() } returns instant

        val next = trigger.nextExecution(triggerContext)
        assertThat(next).isEqualTo(instant.plusMillis(2000))
    }

    @Test
    fun `nextExecution is consistent across multiple invocations`() {
        val trigger =
            AdaptivePollingTrigger(
                minDelay = Duration.ofMillis(2000),
                maxDelay = Duration.ofMillis(64000),
                batchSize = 100,
                clock = clock,
            )

        every { triggerContext.lastCompletion() } returns instant

        val next1 = trigger.nextExecution(triggerContext)
        val next2 = trigger.nextExecution(triggerContext)
        assertThat(next1).isEqualTo(instant.plusMillis(2000))
        assertThat(next2).isEqualTo(instant.plusMillis(2000))
    }

    @ParameterizedTest
    @CsvSource(
        value = [
            "0,  4000",
            "24, 4000",
            "25, 4000",
            "26, 2000",
        ],
    )
    fun `adaptive delay increases on idle`(
        recordCount: Int,
        expectedDelay: Long,
    ) {
        val trigger =
            AdaptivePollingTrigger(
                minDelay = Duration.ofMillis(2000),
                maxDelay = Duration.ofMillis(64000),
                batchSize = 100,
                clock = clock,
            )

        every { triggerContext.lastCompletion() } returns instant

        trigger.onTaskComplete(recordCount)
        val next = trigger.nextExecution(triggerContext)
        assertThat(next).isEqualTo(instant.plusMillis(expectedDelay))
    }

    @Test
    fun `adaptive delay decreases on full batch`() {
        val trigger =
            AdaptivePollingTrigger(
                minDelay = Duration.ofMillis(2000),
                maxDelay = Duration.ofMillis(64000),
                batchSize = 100,
                clock = clock,
            )

        every { triggerContext.lastCompletion() } returns instant

        trigger.onTaskComplete(0)
        val next = trigger.nextExecution(triggerContext)
        assertThat(next).isEqualTo(instant.plusMillis(4000))

        trigger.onTaskComplete(100)
        val next2 = trigger.nextExecution(triggerContext)
        assertThat(next2).isEqualTo(instant.plusMillis(2000))
    }

    @Test
    fun `adaptive delay respect min boundary`() {
        val trigger =
            AdaptivePollingTrigger(
                minDelay = Duration.ofMillis(2000),
                maxDelay = Duration.ofMillis(64000),
                batchSize = 100,
                clock = clock,
            )

        every { triggerContext.lastCompletion() } returns instant

        trigger.onTaskComplete(100)
        val next = trigger.nextExecution(triggerContext)
        assertThat(next).isEqualTo(instant.plusMillis(2000))
    }

    @Test
    fun `adaptive delay respect max boundary`() {
        val trigger =
            AdaptivePollingTrigger(
                minDelay = Duration.ofMillis(2000),
                maxDelay = Duration.ofMillis(4000),
                batchSize = 100,
                clock = clock,
            )

        every { triggerContext.lastCompletion() } returns instant

        trigger.onTaskComplete(0)
        val next = trigger.nextExecution(triggerContext)
        assertThat(next).isEqualTo(instant.plusMillis(4000))

        trigger.onTaskComplete(0)
        val next2 = trigger.nextExecution(triggerContext)
        assertThat(next2).isEqualTo(instant.plusMillis(4000))
    }

    @ParameterizedTest
    @ValueSource(strings = ["PT0S", "PT0.0005S", "PT-1S"])
    fun `rejects min delay below one millisecond`(minDelay: String) {
        assertThatThrownBy {
            AdaptivePollingTrigger(
                minDelay = Duration.parse(minDelay),
                maxDelay = Duration.ofSeconds(8),
                batchSize = 10,
                clock = clock,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("min-interval must be at least 1ms")
    }

    @Test
    fun `rejects max delay below min delay`() {
        assertThatThrownBy {
            AdaptivePollingTrigger(
                minDelay = Duration.ofSeconds(2),
                maxDelay = Duration.ofSeconds(1),
                batchSize = 10,
                clock = clock,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("max-interval must not be less than min-interval")
    }

    @Test
    fun `one millisecond min delay halves down to one millisecond and backs off to max delay`() {
        val trigger =
            AdaptivePollingTrigger(
                minDelay = Duration.ofMillis(1),
                maxDelay = Duration.ofSeconds(8),
                batchSize = 10,
                clock = clock,
            )

        every { triggerContext.lastCompletion() } returns instant

        repeat(13) { trigger.onTaskComplete(0) }
        assertThat(trigger.nextExecution(triggerContext)).isEqualTo(instant.plusMillis(8000))

        repeat(13) { trigger.onTaskComplete(10) }
        assertThat(trigger.nextExecution(triggerContext)).isEqualTo(instant.plusMillis(1))

        trigger.onTaskComplete(5)
        assertThat(trigger.nextExecution(triggerContext)).isEqualTo(instant.plusMillis(1))

        trigger.onTaskComplete(0)
        assertThat(trigger.nextExecution(triggerContext)).isEqualTo(instant.plusMillis(2))
    }
}
