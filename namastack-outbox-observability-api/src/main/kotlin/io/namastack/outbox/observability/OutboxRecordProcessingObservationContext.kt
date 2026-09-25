package io.namastack.outbox.observability

import io.micrometer.observation.transport.ReceiverContext
import io.namastack.outbox.OutboxChannelNameProvider
import io.namastack.outbox.OutboxRecord

/**
 * Observation context for one fully materialized record-processing attempt.
 *
 * The record is the receiver carrier so its persisted propagation headers restore the producer
 * trace before any processor or handler is invoked.
 *
 * @param record Record entering the processor chain.
 * @param channel Logical channel name.
 *
 * @author Aleksander Zamojski
 * @since 1.10.0
 */
class OutboxRecordProcessingObservationContext(
    private val record: OutboxRecord<*>,
    private val channel: String = OutboxChannelNameProvider.DEFAULT_CHANNEL,
) : ReceiverContext<OutboxRecord<*>>({ carrier: OutboxRecord<*>, key: String -> carrier.context[key] }) {
    private var outcome: Outcome? = null

    /**
     * Delivery attempt at observation start (`failureCount + 1`).
     * Snapshotted so later mutations to the record do not change the tag on this observation.
     */
    private val deliveryAttempt: Int = record.failureCount + 1

    init {
        setCarrier(record)
    }

    /** Returns the unique identifier of the record being processed. */
    fun getRecordId(): String = record.id

    /** Returns the business key used for ordering the record. */
    fun getRecordKey(): String = record.key

    /**
     * Returns the current delivery attempt number, calculated as `failureCount + 1`.
     * The value is `1` for a record that has not failed before.
     */
    fun getDeliveryAttempt(): Int = deliveryAttempt

    /** Returns the logical outbox channel name. */
    fun getChannel(): String = channel

    /** Returns the final outcome, or `null` while processing is still active. */
    fun getOutcome(): Outcome? = outcome

    /** Records the final observable outcome before the observation stops. */
    fun setOutcome(outcome: Outcome) {
        this.outcome = outcome
    }

    /**
     * Observable outcome of one record-processing attempt.
     *
     * @property value String representation used as the observation key value.
     */
    enum class Outcome(
        val value: String,
    ) {
        /** The record was completed or deleted. */
        COMPLETED("completed"),

        /** The record remains pending with a future retry time. */
        RETRY_SCHEDULED("retry_scheduled"),

        /** The record reached the terminal failed state. */
        FAILED("failed"),

        /** Processing stopped because a required handler is unavailable on this instance. */
        COMPATIBILITY_DEFERRED("compatibility_deferred"),

        /** Processing ended with an unexpected exception outside the normal Core outcomes. */
        ERROR("error"),
        ;

        override fun toString(): String = value
    }
}
