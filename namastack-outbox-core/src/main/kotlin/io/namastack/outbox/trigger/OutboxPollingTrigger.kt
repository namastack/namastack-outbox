package io.namastack.outbox.trigger

import org.springframework.scheduling.Trigger

/**
 * Interface for outbox polling triggers that determine when the next polling cycle should occur.
 *
 * Extends Spring's [Trigger] interface to provide scheduling capabilities while adding
 * the ability to react to task completion events for adaptive behavior.
 *
 * Implementations can adjust their polling behavior based on the number of records
 * processed in each polling cycle.
 *
 * @author Aleksander Zamojski
 * @since 1.1.0
 */
interface OutboxPollingTrigger : Trigger {
    /**
     * Callback invoked after a polling task completes.
     *
     * This method allows the trigger to adjust its behavior based on the number of records
     * processed. For example, an adaptive trigger might increase the delay if few records
     * were processed or decrease it if many records were found.
     *
     * The default implementation does nothing, making this optional for implementations
     * that don't need to react to task completion.
     *
     * @param recordCount The number of records processed in the completed task
     */
    fun onTaskComplete(recordCount: Int) {
        // No-op by default
    }
}
