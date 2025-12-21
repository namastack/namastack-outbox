package io.namastack.outbox

import io.namastack.outbox.OutboxRecordStatus.NEW
import io.namastack.outbox.partition.PartitionCoordinator
import org.slf4j.LoggerFactory
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.annotation.Scheduled
import java.time.Clock
import java.util.concurrent.CountDownLatch

/**
 * Scheduler for processing outbox records with partition-aware distribution.
 *
 * Loads record keys from assigned partitions in batches, dispatches them to TaskExecutor
 * for parallel processing, coordinates with PartitionCoordinator for partition assignment,
 * and synchronizes batch completion with CountDownLatch.
 *
 * Record processing logic is delegated to OutboxRecordProcessor.
 *
 * @param recordRepository Repository for loading records
 * @param recordProcessor Processor that handles individual record lifecycle
 * @param partitionCoordinator Coordinator for partition assignments
 * @param taskExecutor Executor for parallel key processing
 * @param properties Configuration properties
 * @param clock Clock for time calculations
 *
 * @author Roland Beisel
 * @since 0.2.0
 */
class OutboxProcessingScheduler(
    private val recordRepository: OutboxRecordRepository,
    private val recordProcessor: OutboxRecordProcessor,
    private val partitionCoordinator: PartitionCoordinator,
    private val taskExecutor: TaskExecutor,
    private val properties: OutboxProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(OutboxProcessingScheduler::class.java)

    /**
     * Scheduled processing cycle.
     *
     * Steps:
     * 1. Get assigned partitions
     * 2. Load record keys in batch
     * 3. Submit each key to TaskExecutor
     * 4. Wait for all tasks to complete
     */
    @Scheduled(fixedDelayString = "\${outbox.poll-interval:2000}", scheduler = "outboxDefaultScheduler")
    fun process() {
        try {
            val assignedPartitions = partitionCoordinator.getAssignedPartitionNumbers()
            if (assignedPartitions.isEmpty()) return

            log.debug("Processing {} partitions: {}", assignedPartitions.size, assignedPartitions.sorted())

            val recordKeys =
                recordRepository.findRecordKeysInPartitions(
                    partitions = assignedPartitions,
                    status = NEW,
                    batchSize = properties.batchSize,
                    ignoreRecordKeysWithPreviousFailure = properties.processing.stopOnFirstFailure,
                )

            if (recordKeys.isNotEmpty()) {
                log.debug("Found {} record keys to process", recordKeys.size)
                processBatch(recordKeys)
                log.debug("Finished processing {} record keys", recordKeys.size)
            }
        } catch (ex: Exception) {
            log.error("Error during outbox processing", ex)
        }
    }

    /**
     * Processes batch of record keys in parallel.
     *
     * Uses CountDownLatch to wait for all tasks to complete before returning.
     */
    private fun processBatch(recordKeys: List<String>) {
        val latch = CountDownLatch(recordKeys.size)

        recordKeys.forEach { recordKey ->
            taskExecutor.execute {
                try {
                    processRecordKey(recordKey)
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
    }

    /**
     * Processes all records for a single key sequentially.
     *
     * Maintains ordering within key. Stops early if record not ready for retry
     * or if previous record failed and stopOnFirstFailure is true.
     */
    private fun processRecordKey(recordKey: String) {
        try {
            val records = recordRepository.findIncompleteRecordsByRecordKey(recordKey)
            if (records.isEmpty()) return

            log.trace("Processing {} records for key {}", records.size, recordKey)

            for (record in records) {
                if (!record.canBeRetried(clock)) {
                    log.trace("Skipping record {} - not ready for retry", record.id)
                    break
                }

                val success = recordProcessor.processRecord(record)

                if (!success && properties.processing.stopOnFirstFailure) {
                    log.trace("Stopping key {} processing (stopOnFirstFailure=true)", recordKey)
                    break
                }
            }
        } catch (ex: Exception) {
            log.error("Error processing key {}", recordKey, ex)
        }
    }
}
