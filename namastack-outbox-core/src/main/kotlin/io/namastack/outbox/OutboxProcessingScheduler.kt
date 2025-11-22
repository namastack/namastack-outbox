package io.namastack.outbox

import io.namastack.outbox.OutboxRecordStatus.NEW
import io.namastack.outbox.partition.OutboxRebalanceSignal
import io.namastack.outbox.partition.PartitionCoordinator
import io.namastack.outbox.retry.OutboxRetryPolicy
import org.slf4j.LoggerFactory
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.annotation.Scheduled
import java.time.Clock
import java.util.concurrent.CountDownLatch

/**
 * Scheduler for processing outbox records.
 *
 * This scheduler coordinates with the partition coordinator to only process
 * records assigned to the current instance, enabling horizontal scaling
 * across multiple application instances. It uses a configurable TaskExecutor
 * to enable parallel processing of multiple aggregateIds, while still
 * guaranteeing strict ordering per aggregateId.
 *
 * @param recordRepository Repository for accessing outbox records
 * @param recordProcessor Processor for handling individual records
 * @param partitionCoordinator Coordinator for partition assignments
 * @param taskExecutor TaskExecutor for parallel processing of aggregateIds
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
    private val rebalanceSignal: OutboxRebalanceSignal,
    private val taskExecutor: TaskExecutor,
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
    @Scheduled(fixedDelayString = "\${outbox.poll-interval:2000}")
    fun process() {
        try {
            if (rebalanceSignal.consume()) {
                log.trace("Executing deferred rebalance after batch completion")
                partitionCoordinator.rebalance()
            }

            val assignedPartitions = partitionCoordinator.getAssignedPartitionNumbers()
            if (assignedPartitions.isEmpty()) return

            log.trace(
                "Processing {} partitions: {}",
                assignedPartitions.size,
                assignedPartitions.sorted(),
            )

            val aggregateIds =
                recordRepository.findAggregateIdsInPartitions(
                    partitions = assignedPartitions.toList(),
                    status = NEW,
                    batchSize = properties.batchSize,
                )

            if (aggregateIds.isNotEmpty()) {
                log.trace("Found {} aggregates to process", aggregateIds.size)

                val latch = CountDownLatch(aggregateIds.size)
                aggregateIds.forEach { aggregateId ->
                    taskExecutor.execute {
                        try {
                            processAggregate(aggregateId)
                        } finally {
                            latch.countDown()
                        }
                    }
                }

                latch.await()
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

            if (properties.processing.deleteCompletedRecords) {
                log.debug(
                    "Deleting outbox record {} after successful processing (deleteCompletedRecords=true)",
                    record.id,
                )
                recordRepository.deleteById(record.id)
            } else {
                log.debug("Marking outbox record {} as completed", record.id)
                record.markCompleted(clock)
                recordRepository.save(record)
            }

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
}
