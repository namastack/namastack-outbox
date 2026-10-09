package io.namastack.outbox.handler

/**
 * Determines the final state of a record after its fallback handler returns normally.
 *
 * A fallback that throws always leaves the record `FAILED`, regardless of its disposition.
 *
 * @author Aleksander Zamojski
 * @since 1.11.0
 */
enum class OutboxFallbackDisposition {
    /**
     * The record is marked `COMPLETED`.
     * Later records with the same key are no longer blocked by it.
     */
    COMPLETE,

    /**
     * The record is marked `FAILED` and keeps the failure reason of the primary handler.
     * When stop-on-first-failure is enabled, later records with the same key remain blocked.
     */
    FAIL,
}
