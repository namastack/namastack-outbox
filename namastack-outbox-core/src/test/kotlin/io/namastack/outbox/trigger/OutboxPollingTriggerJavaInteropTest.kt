package io.namastack.outbox.trigger

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.scheduling.support.SimpleTriggerContext
import java.time.Duration
import java.time.Instant

class OutboxPollingTriggerJavaInteropTest {
    private val now = Instant.parse("2026-01-01T12:00:00Z")

    @Test
    fun `java trigger can rely on the default poll result callback`() {
        val trigger: OutboxPollingTrigger = JavaFixedDelayPollingTrigger(Duration.ofSeconds(3))

        trigger.onPollResult(found = 5, requested = 10)

        assertThat(trigger.nextExecution(SimpleTriggerContext(now, now, now))).isEqualTo(now.plusSeconds(3))
    }

    @Test
    fun `java trigger receives poll results`() {
        val trigger = JavaCountingPollingTrigger()

        (trigger as OutboxPollingTrigger).onPollResult(found = 2, requested = 8)

        assertThat(trigger.results).hasSize(1)
        assertThat(trigger.results[0]).containsExactly(2, 8)
    }
}
