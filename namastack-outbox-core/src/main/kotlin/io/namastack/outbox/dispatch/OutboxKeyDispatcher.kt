package io.namastack.outbox.dispatch

/**
 * Strategy that runs one processing run for the partitions owned by this instance.
 *
 * The scheduler calls [dispatch] while it holds a stable partition assignment. An implementation
 * selects record keys from the given partitions, processes them on the processing executor, and
 * returns only after every accepted key task has finished, so the assignment is never released
 * while keys of the run are in progress.
 *
 * An implementation reports every key query to the polling trigger through
 * [io.namastack.outbox.trigger.OutboxPollingTrigger.onPollResult], including a failed query as zero
 * keys found.
 *
 * @author Aleksander Zamojski
 * @since 2.0.0
 */
internal interface OutboxKeyDispatcher {
    /**
     * Runs one processing run for the given partitions.
     *
     * @param partitions Partitions owned by this instance for the duration of the run
     * @param isStopping Reports whether the scheduler is shutting down
     */
    fun dispatch(
        partitions: Set<Int>,
        isStopping: () -> Boolean,
    )

    /**
     * Reports whether a compatibility cooldown is active, in which case the scheduler skips the run.
     *
     * @return `true` while polling must be skipped because of a compatibility failure
     */
    fun isCompatibilityCooldownActive(): Boolean
}
