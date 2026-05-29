package io.namastack.outbox.observability

import io.micrometer.observation.Observation
import io.namastack.outbox.OutboxChannelNameProvider

/**
 * Observation context for the scheduling of outbox records.
 *
 * Created when [io.namastack.outbox.Outbox.schedule] is called and captures
 * information about the payload being scheduled.
 *
 * @param payloadType Simple class name of the payload being scheduled.
 * @param recordKey The record key used for partitioning and ordering.
 * @param channel The logical channel name (defaults to `"default"` in OSS mode).
 *
 * @author Roland Beisel
 * @since 1.7.0
 */
class OutboxScheduleObservationContext(
    val payloadType: String,
    val recordKey: String,
    val channel: String = OutboxChannelNameProvider.DEFAULT_CHANNEL,
) : Observation.Context()
