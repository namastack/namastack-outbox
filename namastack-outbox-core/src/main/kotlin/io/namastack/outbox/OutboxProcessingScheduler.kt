package io.namastack.outbox

import io.namastack.outbox.OutboxRecordStatus.NEW
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
    private val taskExecutor: TaskExecutor,
    private val retryPolicy: OutboxRetryPolicy,
    private val properties: OutboxProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(OutboxProcessingScheduler::class.java)

    /**
     * Scheduled entry point:
     * 1. Consume a pending rebalance signal (rebalance only AFTER previous batch finished).
     * 2. Fetch currently owned partitions (cached via coordinator).
     * 3. Query distinct aggregate IDs with NEW records in those partitions (bounded by batchSize).
     * 4. Process aggregates in parallel using a CountDownLatch to wait until all tasks finish before next cycle.
     *
     * Notes:
     * - Empty partition set => fast exit.
     * - Rebalance never interleaves with in-flight aggregate processing.
     */
    @Scheduled(fixedDelayString = $$"${outbox.poll-interval:2000}", scheduler = "outboxDefaultScheduler")
    fun process() {
        try {
            val assignedPartitions = partitionCoordinator.getAssignedPartitionNumbers()
            if (assignedPartitions.isEmpty()) return

            log.debug(
                "Processing {} partitions: {}",
                assignedPartitions.size,
                assignedPartitions.sorted(),
            )

            val aggregateIds =
                recordRepository.findAggregateIdsInPartitions(
                    partitions = assignedPartitions,
                    status = NEW,
                    batchSize = properties.batchSize,
                    ignoreAggregatesWithPreviousFailure = properties.processing.stopOnFirstFailure,
                )

            if (aggregateIds.isNotEmpty()) {
                log.debug("Found {} aggregates to process", aggregateIds.size)

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
                log.debug("Finished processing {} aggregates", aggregateIds.size)
            }
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
