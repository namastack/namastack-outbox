package io.namastack.outbox.trigger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.scheduling.TriggerContext;

/** Java polling trigger that records every poll result. */
public class JavaCountingPollingTrigger implements OutboxPollingTrigger {
    public final List<int[]> results = new ArrayList<>();

    @Override
    public Instant nextExecution(TriggerContext triggerContext) {
        return triggerContext.lastCompletion();
    }

    @Override
    public void onPollResult(int found, int requested) {
        results.add(new int[] {found, requested});
    }
}
