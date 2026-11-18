package io.namastack.outbox.trigger

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
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

    @Nested
    @DisplayName("Next execution")
    inner class NextExecution {
        @Test
        fun `nextExecution uses current time when no last completion`() {
            val trigger =
                AdaptivePollingTrigger(
                    minDelay = Duration.ofMillis(2000),
                    maxDelay = Duration.ofMillis(60000),
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
                    clock = clock,
                )

            every { triggerContext.lastCompletion() } returns instant

            val next1 = trigger.nextExecution(triggerContext)
            val next2 = trigger.nextExecution(triggerContext)
            assertThat(next1).isEqualTo(instant.plusMillis(2000))
            assertThat(next2).isEqualTo(instant.plusMillis(2000))
        }
    }

    @Nested
    @DisplayName("Poll results")
    inner class PollResults {
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
            found: Int,
            expectedDelay: Long,
        ) {
            val trigger =
                AdaptivePollingTrigger(
                    minDelay = Duration.ofMillis(2000),
                    maxDelay = Duration.ofMillis(64000),
                    clock = clock,
                )

            every { triggerContext.lastCompletion() } returns instant

            trigger.onPollResult(found = found, requested = 100)
            val next = trigger.nextExecution(triggerContext)
            assertThat(next).isEqualTo(instant.plusMillis(expectedDelay))
        }

        @Test
        fun `adaptive delay decreases on full batch`() {
            val trigger =
                AdaptivePollingTrigger(
                    minDelay = Duration.ofMillis(2000),
                    maxDelay = Duration.ofMillis(64000),
                    clock = clock,
                )

            every { triggerContext.lastCompletion() } returns instant

            trigger.onPollResult(found = 0, requested = 100)
            val next = trigger.nextExecution(triggerContext)
            assertThat(next).isEqualTo(instant.plusMillis(4000))

            trigger.onPollResult(found = 100, requested = 100)
            val next2 = trigger.nextExecution(triggerContext)
            assertThat(next2).isEqualTo(instant.plusMillis(2000))
        }

        @Test
        fun `adaptive delay respect min boundary`() {
            val trigger =
                AdaptivePollingTrigger(
                    minDelay = Duration.ofMillis(2000),
                    maxDelay = Duration.ofMillis(64000),
                    clock = clock,
                )

            every { triggerContext.lastCompletion() } returns instant

            trigger.onPollResult(found = 100, requested = 100)
            val next = trigger.nextExecution(triggerContext)
            assertThat(next).isEqualTo(instant.plusMillis(2000))
        }

        @Test
        fun `adaptive delay respect max boundary`() {
            val trigger =
                AdaptivePollingTrigger(
                    minDelay = Duration.ofMillis(2000),
                    maxDelay = Duration.ofMillis(4000),
                    clock = clock,
                )

            every { triggerContext.lastCompletion() } returns instant

            trigger.onPollResult(found = 0, requested = 100)
            val next = trigger.nextExecution(triggerContext)
            assertThat(next).isEqualTo(instant.plusMillis(4000))

            trigger.onPollResult(found = 0, requested = 100)
            val next2 = trigger.nextExecution(triggerContext)
            assertThat(next2).isEqualTo(instant.plusMillis(4000))
        }

        @Test
        fun `one millisecond min delay halves down to one millisecond and backs off to max delay`() {
            val trigger =
                AdaptivePollingTrigger(
                    minDelay = Duration.ofMillis(1),
                    maxDelay = Duration.ofSeconds(8),
                    clock = clock,
                )

            every { triggerContext.lastCompletion() } returns instant

            repeat(13) { trigger.onPollResult(found = 0, requested = 10) }
            assertThat(trigger.nextExecution(triggerContext)).isEqualTo(instant.plusMillis(8000))

            repeat(13) { trigger.onPollResult(found = 10, requested = 10) }
            assertThat(trigger.nextExecution(triggerContext)).isEqualTo(instant.plusMillis(1))

            trigger.onPollResult(found = 5, requested = 10)
            assertThat(trigger.nextExecution(triggerContext)).isEqualTo(instant.plusMillis(1))

            trigger.onPollResult(found = 0, requested = 10)
            assertThat(trigger.nextExecution(triggerContext)).isEqualTo(instant.plusMillis(2))
        }

        @ParameterizedTest
        @CsvSource(
            value = [
                "8, 8, 1000",
                "9, 8, 1000",
                "3, 8, 2000",
                "2, 8, 4000",
                "0, 1, 4000",
                "1, 1, 1000",
                "0, 3, 4000",
            ],
        )
        fun `adaptive rule is relative to the requested key count`(
            found: Int,
            requested: Int,
            expectedDelay: Long,
        ) {
            val trigger =
                AdaptivePollingTrigger(
                    minDelay = Duration.ofMillis(1000),
                    maxDelay = Duration.ofMillis(64000),
                    clock = clock,
                )
            every { triggerContext.lastCompletion() } returns instant
            trigger.onPollResult(found = 0, requested = 4)
            assertThat(trigger.nextExecution(triggerContext)).isEqualTo(instant.plusMillis(2000))

            trigger.onPollResult(found = found, requested = requested)

            assertThat(trigger.nextExecution(triggerContext)).isEqualTo(instant.plusMillis(expectedDelay))
        }

        @ParameterizedTest
        @ValueSource(ints = [0, -1])
        fun `poll result without requested keys leaves the delay unchanged`(requested: Int) {
            val trigger =
                AdaptivePollingTrigger(
                    minDelay = Duration.ofMillis(1000),
                    maxDelay = Duration.ofMillis(64000),
                    clock = clock,
                )
            every { triggerContext.lastCompletion() } returns instant
            trigger.onPollResult(found = 0, requested = 4)

            trigger.onPollResult(found = 0, requested = requested)

            assertThat(trigger.nextExecution(triggerContext)).isEqualTo(instant.plusMillis(2000))
        }
    }

    @Nested
    @DisplayName("Validation")
    inner class Validation {
        @ParameterizedTest
        @ValueSource(strings = ["PT0S", "PT0.0005S", "PT-1S"])
        fun `rejects min delay below one millisecond`(minDelay: String) {
            assertThatThrownBy {
                AdaptivePollingTrigger(
                    minDelay = Duration.parse(minDelay),
                    maxDelay = Duration.ofSeconds(8),
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
                    clock = clock,
                )
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("max-interval must not be less than min-interval")
        }
    }
}
