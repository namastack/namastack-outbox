package io.namastack.outbox.observability

import io.micrometer.observation.transport.ReceiverContext
import io.namastack.outbox.OutboxChannelNameProvider
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.instrumentation.OutboxRecordProcessingOutcome

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
    private var outcome: OutboxRecordProcessingOutcome? = null

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
    fun getDeliveryAttempt(): Int = record.failureCount + 1

    /** Returns the logical outbox channel name. */
    fun getChannel(): String = channel

    /** Returns the final outcome, or `null` while processing is still active. */
    fun getOutcome(): OutboxRecordProcessingOutcome? = outcome

    /** Records the final observable outcome before the observation stops. */
    fun setOutcome(outcome: OutboxRecordProcessingOutcome) {
        this.outcome = outcome
    }
}
