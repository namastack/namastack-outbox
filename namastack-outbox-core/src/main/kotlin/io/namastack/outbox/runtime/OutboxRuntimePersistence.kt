package io.namastack.outbox.runtime

import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.completion.OutboxCompletionWriter
import io.namastack.outbox.instance.OutboxInstanceRepository
import io.namastack.outbox.partition.PartitionAssignmentRepository

/**
 * Resolved persistence repositories for one outbox runtime.
 *
 * The runtime borrows these repositories and does not close their underlying resources.
 * When replacing the record repository through [copy], also replace or clear its completion writer.
 *
 * @property recordRepository Repository for outbox records
 * @property instanceRepository Repository for processor instances
 * @property partitionAssignmentRepository Repository for partition assignments
 * @property completionWriter Optional batch-writing capability for the same record namespace
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
data class OutboxRuntimePersistence(
    val recordRepository: OutboxRecordRepository,
    val instanceRepository: OutboxInstanceRepository,
    val partitionAssignmentRepository: PartitionAssignmentRepository,
    val completionWriter: OutboxCompletionWriter? = null,
)
