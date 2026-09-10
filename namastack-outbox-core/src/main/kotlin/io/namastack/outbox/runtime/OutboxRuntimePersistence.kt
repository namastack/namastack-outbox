package io.namastack.outbox.runtime

import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.instance.OutboxInstanceRepository
import io.namastack.outbox.partition.PartitionAssignmentRepository

/**
 * Fully resolved persistence components for one programmatic outbox runtime.
 *
 * Repositories are selected before runtime construction. Entries in [ownedResources] are closed in
 * reverse order when the runtime closes; borrowed persistence resources must not be included.
 *
 * @property recordRepository Repository for outbox records
 * @property instanceRepository Repository for processor instances
 * @property partitionAssignmentRepository Repository for partition assignments
 * @property ownedResources Persistence resources owned exclusively by the runtime
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
data class OutboxRuntimePersistence(
    val recordRepository: OutboxRecordRepository,
    val instanceRepository: OutboxInstanceRepository,
    val partitionAssignmentRepository: PartitionAssignmentRepository,
    val ownedResources: List<AutoCloseable> = emptyList(),
)
