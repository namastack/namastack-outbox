package io.namastack.outbox.trigger

import org.springframework.scheduling.TriggerContext
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/** Removes the delay after a full, productive batch while retaining the underlying polling strategy. */
internal class DrainOnFullBatchTrigger(
    private val delegate: OutboxPollingTrigger,
    private val batchSize: Int,
    private val clock: Clock,
) : OutboxPollingTrigger {
    private val fullBatch = AtomicBoolean(false)

    override fun onTaskComplete(recordCount: Int) {
        delegate.onTaskComplete(recordCount)
        fullBatch.set(recordCount >= batchSize)
    }

    override fun nextExecution(triggerContext: TriggerContext): Instant? {
        val next = delegate.nextExecution(triggerContext) ?: return null
        return if (fullBatch.get()) triggerContext.lastCompletion() ?: clock.instant() else next
    }
}
