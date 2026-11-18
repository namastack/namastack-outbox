package io.namastack.outbox.trigger

import org.springframework.scheduling.Trigger

/**
 * Interface for outbox polling triggers that determine when the next polling cycle should occur.
 *
 * Extends Spring's [Trigger] interface to provide scheduling capabilities and receives the result
 * of each record key query, so implementations can adjust their polling interval to the workload.
 *
 * In continuous dispatch mode, the key dispatcher also calls [nextExecution] while a run waits for
 * key completions. It passes a context whose last completion is the current time and uses the
 * difference between the returned instant and that time as the wait timeout. A `null` result ends
 * dispatching for the run.
 *
 * @author Aleksander Zamojski
 * @since 1.1.0
 */
interface OutboxPollingTrigger : Trigger {
    /**
     * Callback invoked with the result of a record key query.
     *
     * In batch dispatch mode, it is called once per processing run, after the key query, with the
     * number of keys selected for processing and the configured batch size. In continuous dispatch
     * mode, it is called after every key query of a run with the number of keys selected for
     * processing and the number of free slots at the time of the query. A failed key query is
     * reported as zero keys found.
     *
     * The default implementation does nothing.
     *
     * @param found Number of record keys selected for processing, at most [requested]
     * @param requested Maximum number of record keys the query could select for processing; may be zero
     *
     * @author Aleksander Zamojski
     * @since 2.0.0
     */
    fun onPollResult(
        found: Int,
        requested: Int,
    ) {
        // No-op by default
    }
}
