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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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

    private val lock = ReentrantLock()
    private val processingComplete = lock.newCondition()
    private val shuttingDown = AtomicBoolean(false)
    private val processingActive = AtomicBoolean(false)

    /**
     * Registers the outbox processing job with the task scheduler.
     *
     * Automatically invoked after dependency injection. Schedules [process] to run
     * according to the configured [trigger]. Idempotent - no duplicate scheduling.
     */
    @PostConstruct
    fun registerJob(): Unit =
        lock.withLock {
            if (scheduledTask != null) {
                log.trace("OutboxProcessingScheduler already scheduled")
                return
            }

            scheduledTask = taskScheduler.schedule(this::process, trigger)
            log.info("OutboxProcessingScheduler scheduled with trigger: {}", trigger.javaClass.simpleName)
        }

    /**
     * Unregisters the outbox processing job and performs graceful shutdown.
     *
     * Automatically invoked during application shutdown. Sets shutdown flag to prevent
     * new processing cycles, cancels the scheduled task, and waits for any currently
     * running cycle to complete (up to [OutboxProperties.Processing.shutdownTimeoutSeconds]).
     */
    @PreDestroy
    fun unregisterJob() {
        log.info("Initiating OutboxProcessingScheduler shutdown...")

        shuttingDown.set(true)
        cancelScheduledTask()
        awaitProcessingComplete()

        log.info("OutboxProcessingScheduler shutdown complete")
    }

    /**
     * Main processing cycle. Invoked by the scheduler according to the configured trigger.
     *
     * Loads record keys from assigned partitions and processes them in parallel.
     * Each record is handled by the processor chain (handler → retry → fallback → failure).
     */
    fun process() {
        if (shuttingDown.get()) {
            log.debug("Skipping processing cycle - shutdown in progress")
            return
        }

        markProcessingActive()
        var processedCount = 0

        try {
            processedCount = processAssignedPartitions()
        } catch (ex: Exception) {
            log.error("Error during outbox processing", ex)
        } finally {
            trigger.onTaskComplete(processedCount)
            markProcessingInactive()
        }
    }

    private fun processAssignedPartitions(): Int {
        val partitions = partitionCoordinator.getAssignedPartitionNumbers()
        if (partitions.isEmpty()) return 0

        log.debug("Processing {} partitions: {}", partitions.size, partitions.sorted())

        val recordKeys = loadRecordKeys(partitions)
        if (recordKeys.isEmpty()) return 0

        log.debug("Found {} record keys to process", recordKeys.size)
        processBatch(recordKeys)
        log.debug("Finished processing {} record keys", recordKeys.size)

        return recordKeys.size
    }

    private fun loadRecordKeys(partitions: Set<Int>): List<String> =
        recordRepository.findRecordKeysInPartitions(
            partitions = partitions,
            status = NEW,
            batchSize = properties.batchSize ?: properties.polling.batchSize,
            ignoreRecordKeysWithPreviousFailure = properties.processing.stopOnFirstFailure,
        )

    private fun processBatch(recordKeys: List<String>) {
        val latch = CountDownLatch(recordKeys.size)

        recordKeys.forEach { key ->
            taskExecutor.execute {
                try {
                    processRecordKey(key)
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
    }

    private fun processRecordKey(recordKey: String) {
        try {
            val records = recordRepository.findIncompleteRecordsByRecordKey(recordKey)
            if (records.isEmpty()) return

            log.trace("Processing {} records for key {}", records.size, recordKey)

            for (record in records) {
                if (!processRecord(record)) break
            }
        } catch (ex: Exception) {
            log.error("Error processing key {}", recordKey, ex)
        }
    }

    private fun processRecord(record: OutboxRecord<*>): Boolean {
        if (!record.canBeRetried(clock)) {
            log.trace("Skipping record {} - not ready for retry", record.id)
            return continueOnFailure()
        }

        val success = recordProcessorChain.handle(record)

        return success || continueOnFailure()
    }

    private fun continueOnFailure(): Boolean = !properties.processing.stopOnFirstFailure

    private fun cancelScheduledTask(): Unit =
        lock.withLock {
            scheduledTask?.cancel(false)?.also { cancelled ->
                if (cancelled) log.debug("Scheduled task cancelled")
            }
            scheduledTask = null
        }

    private fun markProcessingActive() {
        processingActive.set(true)
    }

    private fun markProcessingInactive(): Unit =
        lock.withLock {
            processingActive.set(false)
            processingComplete.signalAll()
        }

    private fun awaitProcessingComplete(): Unit =
        lock.withLock {
            if (!processingActive.get()) return

            val timeout = properties.processing.shutdownTimeoutSeconds
            log.debug("Waiting up to {}s for current processing cycle to complete...", timeout)

            val completed = processingComplete.await(timeout, TimeUnit.SECONDS)

            if (completed) {
                log.debug("Processing cycle completed")
            } else {
                log.warn("Timeout after {}s, proceeding with shutdown", timeout)
            }
        }
}
