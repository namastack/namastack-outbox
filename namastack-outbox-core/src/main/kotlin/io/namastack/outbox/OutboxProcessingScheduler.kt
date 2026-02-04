package io.namastack.outbox

import io.namastack.outbox.OutboxRecordStatus.NEW
import io.namastack.outbox.partition.PartitionCoordinator
import io.namastack.outbox.processor.OutboxRecordProcessor
import io.namastack.outbox.trigger.OutboxPollingTrigger
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.TaskScheduler
import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledFuture

/**
 * Scheduler for processing outbox records with partition-aware distribution.
 *
 * Loads record keys from assigned partitions in batches, dispatches them to TaskExecutor
 * for parallel processing, and coordinates with PartitionCoordinator for partition assignment.
 *
 * Record processing is delegated to a chain of processors that handle the complete
 * record lifecycle (dispatch to handler, retry logic, fallback invocation, failure marking).
 *
 * @param trigger Polling trigger that determines when the next processing cycle should occur
 * @param taskScheduler Spring TaskScheduler for scheduling the processing job
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
    private val trigger: OutboxPollingTrigger,
    private val taskScheduler: TaskScheduler,
    private val recordRepository: OutboxRecordRepository,
    private val recordProcessorChain: OutboxRecordProcessor,
    private val partitionCoordinator: PartitionCoordinator,
    private val taskExecutor: TaskExecutor,
    private val properties: OutboxProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(OutboxProcessingScheduler::class.java)
    private var scheduledTask: ScheduledFuture<*>? = null

    /**
     * Registers the outbox processing job with the task scheduler.
     *
     * This method is automatically invoked after dependency injection is complete.
     * It schedules the [process] method to run according to the configured [trigger].
     * The method is idempotent - if a task is already scheduled, no additional task is created.
     *
     * Thread-safe through synchronization.
     */
    @PostConstruct
    @Synchronized
    fun registerJob() {
        if (scheduledTask == null) {
            scheduledTask = taskScheduler.schedule(this::process, trigger)
            log.info("OutboxProcessingScheduler scheduled with trigger: {}", trigger.javaClass.simpleName)
        } else {
            log.trace("OutboxProcessingScheduler already scheduled")
        }
    }

    /**
     * Unregisters the outbox processing job from the task scheduler.
     *
     * This method is automatically invoked during application shutdown.
     * It attempts to cancel the scheduled task gracefully (without interrupting if already running).
     * After cancellation, the task reference is cleared.
     *
     * Thread-safe through synchronization.
     */
    @PreDestroy
    @Synchronized
    fun unregisterJob() {
        val cancelled = scheduledTask?.cancel(false) ?: false
        if (cancelled) {
            log.info("OutboxProcessingScheduler task cancel")
        }
        scheduledTask = null
    }

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
    fun process() {
        var recordKeyCount = 0
        try {
            val assignedPartitions = partitionCoordinator.getAssignedPartitionNumbers()
            if (assignedPartitions.isEmpty()) return

            log.debug("Processing {} partitions: {}", assignedPartitions.size, assignedPartitions.sorted())

            val recordKeys =
                recordRepository.findRecordKeysInPartitions(
                    partitions = assignedPartitions,
                    status = NEW,
                    batchSize = properties.batchSize ?: properties.polling.batchSize,
                    ignoreRecordKeysWithPreviousFailure = properties.processing.stopOnFirstFailure,
                )

            recordKeyCount = recordKeys.size

            if (recordKeys.isNotEmpty()) {
                log.debug("Found {} record keys to process", recordKeyCount)
                processBatch(recordKeys)
                log.debug("Finished processing {} record keys", recordKeyCount)
            }
        } catch (ex: Exception) {
            log.error("Error during outbox processing", ex)
        } finally {
            trigger.onTaskComplete(recordKeyCount)
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
