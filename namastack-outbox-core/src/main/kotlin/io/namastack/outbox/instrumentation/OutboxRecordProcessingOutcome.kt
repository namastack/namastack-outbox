package io.namastack.outbox.instrumentation

/**
 * Final outcome of a normally completed record-processing attempt.
 *
 * Exceptional completion is represented by the original exception propagating from the processor
 * chain rather than by an outcome value.
 *
 * @author Aleksander Zamojski
 * @since 1.10.0
 */
enum class OutboxRecordProcessingOutcome {
    /** The record was completed or deleted. */
    COMPLETED,

    /** The record remains pending with a future retry time. */
    RETRY_SCHEDULED,

    /** The record reached the terminal failed state. */
    FAILED,
}
