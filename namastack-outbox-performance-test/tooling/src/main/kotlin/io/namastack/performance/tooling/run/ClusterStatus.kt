package io.namastack.performance.tooling.run

internal data class ClusterStatus(
    val activeInstances: Long,
    val assignedPartitions: Long,
)
