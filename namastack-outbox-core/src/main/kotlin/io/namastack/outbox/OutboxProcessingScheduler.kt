package io.namastack.outbox

import io.namastack.outbox.OutboxRecordStatus.NEW
import io.namastack.outbox.partition.PartitionCoordinator
import io.namastack.outbox.retry.OutboxRetryPolicy
import org.slf4j.LoggerFactory
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.annotation.Scheduled
import java.time.Clock

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
    private val taskExecutor: TaskExecutor,
    private val retryPolicy: OutboxRetryPolicy,
    private val properties: OutboxProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(OutboxProcessingScheduler::class.java)

    /**
     * Scheduled method to process outbox records assigned to this instance.
     *
     * Steps:
     * 1. Checks for assigned partitions and exits early if none.
     * 2. Iteratively fetches and processes aggregate IDs with NEW records in assigned partitions.
     * 3. Uses a limiter and TaskExecutor to process aggregates in parallel, ensuring ordering per aggregateId.
     * 4. Handles rebalancing and waits for all tasks to complete before finishing.
     */
    @Scheduled(fixedDelayString = $$"${outbox.poll-interval:2000}", scheduler = "outboxDefaultScheduler")
    fun process() {
        try {
            var previouslyAssignedPartitions = partitionCoordinator.getAssignedPartitionNumbers()
            if (previouslyAssignedPartitions.isEmpty()) return

            log.debug(
                "Processing {} partitions: {}",
                previouslyAssignedPartitions.size,
                previouslyAssignedPartitions.sorted(),
            )

            val limiter = OutboxProcessingLimiter(properties.batchSize)

            while (true) {
                val assignedPartitions = partitionCoordinator.getAssignedPartitionNumbers()
                if (assignedPartitions.isEmpty()) return
                if (assignedPartitions != previouslyAssignedPartitions) break

                val unprocessedAggregateIds = limiter.getUnprocessedIds()
                val aggregateIds =
                    recordRepository.findAggregateIdsInPartitions(
                        partitions = assignedPartitions,
                        status = NEW,
                        batchSize = properties.batchSize * 2, // Fetch extra to account for unprocessed
                        ignoreAggregatesWithPreviousFailure = properties.processing.stopOnFirstFailure,
                    )
                val nextAggregateIds = aggregateIds - unprocessedAggregateIds
                if (nextAggregateIds.isEmpty()) break

                log.debug("Found {} aggregates to process", nextAggregateIds.size)

                nextAggregateIds.forEach { aggregateId ->
                    limiter.acquire(aggregateId)
                    taskExecutor.execute {
                        try {
                            processAggregate(aggregateId)
                        } finally {
                            limiter.release(aggregateId)
                        }
                    }
                }

                if (nextAggregateIds.size < properties.batchSize) break

                previouslyAssignedPartitions = assignedPartitions
            }

            limiter.awaitAll()
            log.debug("Finished processing {} aggregates", limiter.getProcessedCount())
        } catch (ex: Exception) {
            log.error("Error during partition-aware outbox processing", ex)
        }
    }

    /**
     * Process all incomplete records for one aggregate in creation order.
     * Stops early when:
     *  - First non-retriable (future retry time) record encountered (maintains ordering gap).
     *  - A failure occurs and stopOnFirstFailure is enabled.
     */
    private fun processAggregate(aggregateId: String) {
        try {
            val records = recordRepository.findIncompleteRecordsByAggregateId(aggregateId)

            if (records.isEmpty()) {
                return
            }

            log.trace("Processing {} records for aggregate {}", records.size, aggregateId)

            for (record in records) {
                if (!record.canBeRetried(clock)) {
                    log.trace("Skipping record {} - not ready for retry", record.id)
                    break
                }

                val success = processRecord(record)

                if (!success && properties.processing.stopOnFirstFailure) {
                    log.trace(
                        "Stopping aggregate {} processing due to failure (stopOnFirstFailure=true)",
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
     * Process a single record:
     *  - Invoke business processor
     *  - On success either delete (if configured) or mark as completed & persist
     *  - On exception delegate to failure handler
     */
    private fun processRecord(record: OutboxRecord): Boolean =
        try {
            log.trace(
                "Processing {} for {} (partition {})",
                record.eventType,
                record.aggregateId,
                record.partition,
            )

            recordProcessor.process(record)

            if (properties.processing.deleteCompletedRecords) {
                log.trace(
                    "Deleting outbox record {} after successful processing (deleteCompletedRecords=true)",
                    record.id,
                )
                recordRepository.deleteById(record.id)
            } else {
                log.trace("Marking outbox record {} as completed", record.id)
                record.markCompleted(clock)
                recordRepository.save(record)
            }

            log.trace("Successfully processed {} for {}", record.eventType, record.aggregateId)
            true
        } catch (ex: Exception) {
            handleFailure(record, ex)
            false // single false (removed duplicate)
        }

    /**
     * Handle a failed record:
     *  - Increment retry counter
     *  - Decide FAILED vs scheduled retry based on maxRetries & retryPolicy
     *  - Persist updated state
     */
    private fun handleFailure(
        record: OutboxRecord,
        ex: Exception,
    ) {
        log.debug("Failed {} for {}: {}", record.eventType, record.aggregateId, ex.message)

        val retriesExhausted = record.retriesExhausted(properties.retry.maxRetries)
        if (retriesExhausted || !retryPolicy.shouldRetry(ex)) {
            record.markFailed()

            log.warn(
                "Record {} for aggregate {} marked as FAILED after {} retries",
                record.id,
                record.aggregateId,
                record.retryCount,
            )
        } else {
            val delay = retryPolicy.nextDelay(record.retryCount)
            record.scheduleNextRetry(delay, clock)
            record.incrementRetryCount()

            log.debug(
                "Scheduled retry #{} for record {} in {}",
                record.retryCount,
                record.id,
                delay,
            )
        }

        recordRepository.save(record)
    }
}
