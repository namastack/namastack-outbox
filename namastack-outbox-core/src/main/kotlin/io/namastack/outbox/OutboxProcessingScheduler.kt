package io.namastack.outbox

import io.namastack.outbox.OutboxRecordStatus.NEW
import io.namastack.outbox.partition.PartitionCoordinator
import io.namastack.outbox.processor.OutboxRecordProcessor
import org.slf4j.LoggerFactory
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.annotation.Scheduled
import java.time.Clock
import java.util.concurrent.CountDownLatch

/**
 * Scheduler for processing outbox records with partition-aware distribution.
 *
 * Loads record keys from assigned partitions in batches, dispatches them to TaskExecutor
 * for parallel processing, and coordinates with PartitionCoordinator for partition assignment.
 *
 * Record processing is delegated to a chain of processors that handle the complete
 * record lifecycle (dispatch to handler, retry logic, fallback invocation, failure marking).
 *
 * @param recordRepository Repository for loading records
 * @param recordProcessorChain Root processor of the chain (typically PrimaryOutboxRecordProcessor)
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
    private val recordProcessorChain: OutboxRecordProcessor,
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
     * 3. Submit each key to TaskExecutor for parallel processing
     * 4. Wait for all tasks to complete
     *
     * Each record is processed through the processor chain which handles
     * dispatching, retry logic, fallback invocation, and failure marking.
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
     * Maintains ordering within key. Each record is passed through the processor chain.
     * Stops early if record not ready for retry or if processor chain returns false
     * and stopOnFirstFailure is enabled.
     */
    private fun processRecordKey(recordKey: String) {
        try {
            val records = recordRepository.findIncompleteRecordsByRecordKey(recordKey)
            if (records.isEmpty()) return

            log.trace("Processing {} records for key {}", records.size, recordKey)

            for (record in records) {
                val shouldContinue = processRecord(record, recordKey)
                if (!shouldContinue) break
            }
        } catch (ex: Exception) {
            log.error("Error processing key {}", recordKey, ex)
        }
    }

    /**
     * Processes single record. Returns true if processing should continue for this key.
     */
    private fun processRecord(
        record: OutboxRecord<*>,
        recordKey: String,
    ): Boolean {
        if (!record.canBeRetried(clock)) {
            log.trace("Skipping record {} - not ready for retry", record.id)
            return shouldContinueProcessing(recordKey)
        }

        return recordProcessorChain.handle(record) || shouldContinueProcessing(recordKey)
    }

    /**
     * Determines if processing should continue based on stopOnFirstFailure setting.
     */
    private fun shouldContinueProcessing(recordKey: String): Boolean {
        if (properties.processing.stopOnFirstFailure) {
            log.trace("Stopping key {} processing (stopOnFirstFailure=true)", recordKey)
            return false
        }

        return true
    }
}
