package io.namastack.outbox.observability

import io.micrometer.common.docs.KeyName
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.docs.MeterDocumentation

/**
 * Central place for Micrometer meter definitions used by this project.
 *
 * Keeping names/descriptions here avoids string duplication across binders and
 * makes it easier to keep the public metrics surface stable.
 */
enum class OutboxMeters(
    /** Human-facing metric description used in binder `.description(...)`. */
    val description: String,
) : MeterDocumentation {
    /** Count of outbox records by status. */
    RECORDS_COUNT(
        description = "Count of outbox records by status",
    ) {
        override fun getName(): String = "outbox.records.count"

        override fun getType(): Meter.Type = Meter.Type.GAUGE

        override fun getBaseUnit(): String? = null

        override fun getKeyNames(): Array<out KeyName> = arrayOf(Keys.RecordStatus)
    },

    /** Number of partitions assigned to this instance. */
    PARTITIONS_ASSIGNED_COUNT(
        description = "Number of partitions assigned to this instance",
    ) {
        override fun getName(): String = "outbox.partitions.assigned.count"

        override fun getType(): Meter.Type = Meter.Type.GAUGE

        override fun getBaseUnit(): String? = null
    },

    /** Total number of pending records across all assigned partitions. */
    PARTITIONS_PENDING_RECORDS_TOTAL(
        description = "Total number of pending records across all assigned partitions",
    ) {
        override fun getName(): String = "outbox.partitions.pending.records.total"

        override fun getType(): Meter.Type = Meter.Type.GAUGE

        override fun getBaseUnit(): String? = null
    },

    /** Maximum number of pending records in any assigned partition. */
    PARTITIONS_PENDING_RECORDS_MAX(
        description = "Maximum number of pending records in any assigned partition",
    ) {
        override fun getName(): String = "outbox.partitions.pending.records.max"

        override fun getType(): Meter.Type = Meter.Type.GAUGE

        override fun getBaseUnit(): String? = null
    },

    /** Average number of pending records per assigned partition. */
    PARTITIONS_PENDING_RECORDS_AVG(
        description = "Average number of pending records per assigned partition",
    ) {
        override fun getName(): String = "outbox.partitions.pending.records.avg"

        override fun getType(): Meter.Type = Meter.Type.GAUGE

        override fun getBaseUnit(): String? = null
    },

    /** Total number of active instances in the outbox cluster. */
    CLUSTER_INSTANCES_TOTAL(
        description = "Total number of active instances in the outbox cluster",
    ) {
        override fun getName(): String = "outbox.cluster.instances.total"

        override fun getType(): Meter.Type = Meter.Type.GAUGE

        override fun getBaseUnit(): String? = null
    },

    /** Total number of partitions in the outbox cluster. */
    CLUSTER_PARTITIONS_TOTAL(
        description = "Total number of partitions in the outbox cluster",
    ) {
        override fun getName(): String = "outbox.cluster.partitions.total"

        override fun getType(): Meter.Type = Meter.Type.GAUGE

        override fun getBaseUnit(): String? = null
    },

    /** Average number of partitions assigned per instance. */
    CLUSTER_PARTITIONS_AVG_PER_INSTANCE(
        description = "Average number of partitions assigned per instance",
    ) {
        override fun getName(): String = "outbox.cluster.partitions.avg_per_instance"

        override fun getType(): Meter.Type = Meter.Type.GAUGE

        override fun getBaseUnit(): String? = null
    },

    /** Number of partitions currently unassigned (no instance owner). */
    CLUSTER_PARTITIONS_UNASSIGNED_COUNT(
        description = "Number of partitions currently unassigned (no instance owner)",
    ) {
        override fun getName(): String = "outbox.cluster.partitions.unassigned.count"

        override fun getType(): Meter.Type = Meter.Type.GAUGE

        override fun getBaseUnit(): String? = null
    },

    /** 1 if partition is currently unassigned, else 0. */
    CLUSTER_PARTITIONS_UNASSIGNED_FLAG(
        description = "1 if partition is currently unassigned, else 0",
    ) {
        override fun getName(): String = "outbox.cluster.partitions.unassigned.flag"

        override fun getType(): Meter.Type = Meter.Type.GAUGE

        override fun getBaseUnit(): String? = null

        override fun getKeyNames(): Array<out KeyName> = arrayOf(Keys.Partition)
    },
    ;

    enum class Keys : KeyName {
        RecordStatus {
            override fun asString(): String = "status"
        },
        Partition {
            override fun asString(): String = "partition"
        },
    }
}
