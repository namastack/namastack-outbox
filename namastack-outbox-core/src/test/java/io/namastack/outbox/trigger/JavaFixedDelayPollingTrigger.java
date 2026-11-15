package io.namastack.outbox.trigger;

import java.time.Duration;
import java.time.Instant;
import org.springframework.scheduling.TriggerContext;

/** Java polling trigger that relies on the default {@code onPollResult}. */
public class JavaFixedDelayPollingTrigger implements OutboxPollingTrigger {
    private final Duration delay;

    public JavaFixedDelayPollingTrigger(Duration delay) {
        this.delay = delay;
    }

    @Override
    public Instant nextExecution(TriggerContext triggerContext) {
        Instant lastCompletion = triggerContext.lastCompletion();
        return lastCompletion == null ? null : lastCompletion.plus(delay);
    }
}
