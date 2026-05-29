package io.namastack.outbox.observability

/**
 * Canonical Micrometer meter names produced by the outbox observability module.
 *
 * Observation names are used for event-driven timers and traces. Gauge names represent
 * current outbox runtime state.
 *
 * @author Roland Beisel
 * @since 1.7.0
 */
object OutboxMetricNames {
    const val RECORD_PROCESS: String = "outbox.record.process"
    const val RECORD_SCHEDULE: String = "outbox.record.schedule"

    const val RECORDS: String = "outbox.records"
    const val INSTANCE_PARTITIONS_ASSIGNED: String = "outbox.instance.partitions.assigned"
    const val INSTANCE_RECORDS_PENDING: String = "outbox.instance.records.pending"
    const val CLUSTER_INSTANCES_ACTIVE: String = "outbox.cluster.instances.active"
    const val CLUSTER_PARTITIONS_UNASSIGNED: String = "outbox.cluster.partitions.unassigned"
}

/**
 * Canonical Micrometer key names used by the outbox observability module.
 *
 * Low-cardinality keys are safe for metrics. High-cardinality keys are intended for
 * traces and log correlation only.
 *
 * @author Roland Beisel
 * @since 1.7.0
 */
object OutboxMetricKeyNames {
    object LowCardinality {
        const val CHANNEL: String = "outbox.channel"
        const val RECORD_STATUS: String = "outbox.record.status"
        const val HANDLER_KIND: String = "outbox.handler.kind"
        const val HANDLER_ID: String = "outbox.handler.id"
    }

    object HighCardinality {
        const val RECORD_ID: String = "outbox.record.id"
        const val RECORD_KEY: String = "outbox.record.key"
        const val DELIVERY_ATTEMPT: String = "outbox.delivery.attempt"
        const val SCHEDULE_RECORD_KEY: String = "outbox.schedule.record.key"
        const val SCHEDULE_PAYLOAD_TYPE: String = "outbox.schedule.payload.type"
    }
}
