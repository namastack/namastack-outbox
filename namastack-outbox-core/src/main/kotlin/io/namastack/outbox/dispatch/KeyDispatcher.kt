package io.namastack.outbox.dispatch

/**
 * Strategy that runs one processing run for the partitions owned by this instance.
 *
 * The scheduler calls [dispatch] while it holds a stable partition assignment. An implementation
 * selects record keys from the given partitions, processes them on the processing executor, and
 * returns only after every accepted key task has finished, so the assignment is never released
 * while keys of the run are in progress.
 *
 * This type is part of the processing runtime. It is not intended to be implemented by applications.
 *
 * @author Aleksander Zamojski
 * @since 2.0.0
 */
interface KeyDispatcher {
    /**
     * Whether this dispatcher reports each key query to the polling trigger itself.
     *
     * When `false`, the scheduler reports the number of processed keys once at the end of each run.
     */
    val reportsPollResults: Boolean

    /**
     * Runs one processing run for the given partitions.
     *
     * @param partitions Partitions owned by this instance for the duration of the run
     * @param isStopping Reports whether the scheduler is shutting down
     * @return Number of record keys submitted for processing during the run
     */
    fun dispatch(
        partitions: Set<Int>,
        isStopping: () -> Boolean,
    ): Int

    /**
     * Reports whether a compatibility cooldown is active, in which case the scheduler skips the run.
     *
     * @return `true` while polling must be skipped because of a compatibility failure
     */
    fun isCompatibilityCooldownActive(): Boolean
}
