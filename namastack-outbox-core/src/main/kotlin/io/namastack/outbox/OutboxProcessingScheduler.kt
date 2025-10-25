package io.namastack.outbox

import io.namastack.outbox.OutboxRecordStatus.NEW
import io.namastack.outbox.partition.PartitionCoordinator
import io.namastack.outbox.partition.PartitionProcessingStats
import io.namastack.outbox.retry.OutboxRetryPolicy
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import java.time.Clock

/**
 * Scheduler for processing outbox records.
 *
 * This scheduler coordinates with the partition coordinator to only process
 * records assigned to the current instance, enabling horizontal scaling
 * across multiple application instances.
 *
 * @param recordRepository Repository for accessing outbox records
 * @param recordProcessor Processor for handling individual records
 * @param partitionCoordinator Coordinator for partition assignments
 * @param instanceRegistry Registry for instance management
 * @param retryPolicy Policy for determining retry behavior
 * @param properties Configuration properties
 * @param clock Clock for time-based operations
 *
 * @author Roland Beisel
 * @since 0.2.0
 */
class OutboxProcessingScheduler(
    private val recordRepository: OutboxRecordRepository,
    private val recordProcessor: OutboxRecordProcessor,
    private val partitionCoordinator: PartitionCoordinator,
    private val instanceRegistry: OutboxInstanceRegistry,
    private val retryPolicy: OutboxRetryPolicy,
    private val properties: OutboxProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(OutboxProcessingScheduler::class.java)

    /**
     * Main processing method that runs on a scheduled interval.
     *
     * Only processes records from partitions assigned to this instance,
     * ensuring proper load distribution and avoiding conflicts.
     */
    @Scheduled(fixedDelayString = "\${outbox.poll-interval}")
    fun process() {
        try {
            val myInstanceId = instanceRegistry.getCurrentInstanceId()
            val assignedPartitions = partitionCoordinator.getAssignedPartitions(myInstanceId)

            if (assignedPartitions.isEmpty()) {
                log.debug("No partitions assigned to instance {} - waiting for rebalancing", myInstanceId)
                return
            }

            log.debug(
                "Processing {} partitions for instance {}: {}",
                assignedPartitions.size,
                myInstanceId,
                assignedPartitions,
            )

            val aggregateIds =
                recordRepository.findAggregateIdsInPartitions(
                    partitions = assignedPartitions,
                    status = NEW,
                    batchSize = properties.batchSize,
                )

            if (aggregateIds.isNotEmpty()) {
                log.debug(
                    "Found {} aggregates to process: {}",
                    aggregateIds.size,
                    aggregateIds.take(5).plus(if (aggregateIds.size > 5) "..." else ""),
                )

                aggregateIds.forEach { aggregateId ->
                    processAggregate(aggregateId)
                }
            }
        } catch (ex: Exception) {
            log.error("Error during partition-aware outbox processing", ex)
        }
    }

    /**
     * Processes all incomplete records for a specific aggregate.
     *
     * @param aggregateId The aggregate ID to process records for
     */
    private fun processAggregate(aggregateId: String) {
        try {
            val records = recordRepository.findAllIncompleteRecordsByAggregateId(aggregateId)

            if (records.isEmpty()) {
                return
            }

            log.debug("Processing {} records for aggregate {}", records.size, aggregateId)

            for (record in records) {
                if (!record.canBeRetried(clock)) {
                    log.debug("Skipping record {} - not ready for retry", record.id)
                    break
                }

                val success = processRecord(record)

                if (!success && properties.processing.stopOnFirstFailure) {
                    log.debug(
                        "🛑 Stopping aggregate {} processing due to failure (stopOnFirstFailure=true)",
                        aggregateId,
                    )
                    break
                }
            }
        } catch (ex: Exception) {
            log.error("Error processing aggregate {}", aggregateId, ex)
        }
    }

    /**
     * Processes a single outbox record.
     *
     * @param record The record to process
     * @return true if processing was successful, false otherwise
     */
    private fun processRecord(record: OutboxRecord): Boolean =
        try {
            log.debug(
                "⏳ Processing {} for {} (partition {})",
                record.eventType,
                record.aggregateId,
                record.partition,
            )

            recordProcessor.process(record)
            record.markCompleted(clock)
            recordRepository.save(record)

            log.debug("✅ Successfully processed {} for {}", record.eventType, record.aggregateId)
            true
        } catch (ex: Exception) {
            handleFailure(record, ex)
            false
        }

    /**
     * Handles processing failures by updating retry count and scheduling next retry.
     *
     * @param record The record that failed processing
     * @param ex The exception that caused the failure
     */
    private fun handleFailure(
        record: OutboxRecord,
        ex: Exception,
    ) {
        log.debug("❌ Failed {} for {}: {}", record.eventType, record.aggregateId, ex.message)

        record.incrementRetryCount()
        if (record.retriesExhausted(properties.retry.maxRetries) || !retryPolicy.shouldRetry(ex)) {
            record.markFailed()
            log.warn(
                "🚫 Record {} for aggregate {} marked as FAILED after {} retries",
                record.id,
                record.aggregateId,
                record.retryCount,
            )
        } else {
            val delay = retryPolicy.nextDelay(record.retryCount)
            record.scheduleNextRetry(delay, clock)
            log.debug(
                "🔄 Scheduled retry #{} for record {} in {}",
                record.retryCount,
                record.id,
                delay,
            )
        }

        recordRepository.save(record)
    }

    /**
     * Gets statistics about current partition processing load.
     */
    fun getProcessingStats(): PartitionProcessingStats {
        val myInstanceId = instanceRegistry.getCurrentInstanceId()
        val assignedPartitions = partitionCoordinator.getAssignedPartitions(myInstanceId)

        val pendingRecordsPerPartition =
            assignedPartitions.associateWith { partition ->
                recordRepository.countRecordsByPartition(partition, NEW)
            }

        val totalPendingRecords = pendingRecordsPerPartition.values.sum()

        return PartitionProcessingStats(
            instanceId = myInstanceId,
            assignedPartitions = assignedPartitions,
            pendingRecordsPerPartition = pendingRecordsPerPartition,
            totalPendingRecords = totalPendingRecords,
        )
    }
}
