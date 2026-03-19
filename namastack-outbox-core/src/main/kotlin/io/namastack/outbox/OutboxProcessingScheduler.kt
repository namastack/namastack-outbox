package io.namastack.outbox

import io.micrometer.observation.ObservationRegistry
import io.namastack.outbox.OutboxRecordStatus.NEW
import io.namastack.outbox.partition.PartitionCoordinator
import io.namastack.outbox.processor.OutboxRecordProcessor
import io.namastack.outbox.trigger.OutboxPollingTrigger
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.support.ScheduledMethodRunnable
import java.lang.reflect.Method
import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
 * @param observationRegistry Supplier for obtaining the Micrometer [ObservationRegistry]
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
@OpenForProxy
class OutboxProcessingScheduler(
    private val trigger: OutboxPollingTrigger,
    private val taskScheduler: TaskScheduler,
    private val observationRegistry: () -> ObservationRegistry,
    private val recordRepository: OutboxRecordRepository,
    private val recordProcessorChain: OutboxRecordProcessor,
    private val partitionCoordinator: PartitionCoordinator,
    private val taskExecutor: TaskExecutor,
    private val properties: OutboxProperties,
    private val clock: Clock,
) : SmartLifecycle {
    companion object {
        const val SCHEDULER_NAME: String = "outboxDefaultScheduler"

        private val SCHEDULE_METHOD_NAME: String = (OutboxProcessingScheduler::process).name
        private val SCHEDULE_METHOD: Method = OutboxProcessingScheduler::class.java.getMethod(SCHEDULE_METHOD_NAME)
    }

    private val log = LoggerFactory.getLogger(OutboxProcessingScheduler::class.java)

    private val beanState = BeanState(properties.processing.shutdownTimeoutSeconds)
    private var scheduledTask: ScheduledFuture<*>? = null

    override fun getPhase(): Int = 0

    /**
     * Registers the outbox processing job with the task scheduler.
     *
     * Automatically invoked after dependency injection. Schedules [process] to run
     * according to the configured [trigger]. Idempotent - no duplicate scheduling.
     */
    override fun start() {
        beanState.scheduleJob {
            val runnable = ScheduledMethodRunnable(this, SCHEDULE_METHOD, SCHEDULER_NAME, observationRegistry)
            scheduledTask = taskScheduler.schedule(runnable, trigger)
            log.info("OutboxProcessingScheduler scheduled (trigger={})", trigger.javaClass.simpleName)
        }
    }

    /**
     * Unregisters the outbox processing job and performs graceful shutdown.
     *
     * Automatically invoked during application shutdown. Sets shutdown flag to prevent
     * new processing cycles, cancels the scheduled task, and waits for any currently
     * running cycle to complete (up to [OutboxProperties.Processing.shutdownTimeoutSeconds]).
     */
    override fun stop() {
        log.info("Initiating OutboxProcessingScheduler shutdown...")
        beanState.cancelScheduledJob {
            scheduledTask?.cancel(false)?.also { cancelled ->
                if (cancelled) {
                    log.debug("Cancelled scheduled outbox task")
                } else {
                    log.debug("Scheduled outbox task cancellation was not applied (already cancelled or completed)")
                }
            }
            scheduledTask = null
        }
        log.info("OutboxProcessingScheduler shutdown complete")
    }

    override fun isRunning(): Boolean = beanState.isStarted()

    /**
     * Main processing cycle. Invoked by the scheduler according to the configured trigger.
     *
     * Loads record keys from assigned partitions and processes them in parallel.
     * Each record is handled by the processor chain (handler → retry → fallback → failure).
     */
    fun process() {
        if (!beanState.startProcessing()) return

        var processedCount = 0

        try {
            processedCount = processAssignedPartitions()
        } catch (ex: Exception) {
            log.error("Error during outbox processing", ex)
        } finally {
            trigger.onTaskComplete(processedCount)
            beanState.stopProcessing()
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

    class BeanState(
        private val shutdownTimeoutSeconds: Long,
    ) {
        enum class State {
            STOPPED,
            IDLE,
            RUNNING,
            SHUTTING_DOWN,
        }

        private val log = LoggerFactory.getLogger(OutboxProcessingScheduler::class.java)

        private val lock = ReentrantLock()
        private val processingComplete = lock.newCondition()
        private val state = AtomicReference(State.STOPPED)

        fun isStarted(): Boolean = state.get() != State.STOPPED

        fun scheduleJob(schedule: () -> Unit) =
            lock.withLock {
                if (state.get() == State.STOPPED) {
                    schedule.invoke()
                    transitionTo(State.IDLE, "scheduler registered")
                } else {
                    log.trace("Ignoring schedule request because scheduler is already active (state={})", state.get())
                }
            }

        fun startProcessing(): Boolean =
            lock.withLock {
                return if (state.get() == State.IDLE) {
                    transitionTo(State.RUNNING, "processing cycle started")
                    true
                } else {
                    log.trace("Skipping processing cycle because scheduler is not idle (state={})", state.get())
                    false
                }
            }

        fun stopProcessing(): Unit =
            lock.withLock {
                if (state.get() == State.RUNNING) {
                    transitionTo(State.IDLE, "processing cycle finished")
                } else if (state.get() == State.SHUTTING_DOWN) {
                    log.trace("Processing cycle finished during shutdown. Notifying waiting thread")
                    processingComplete.signalAll()
                } else {
                    log.trace("Ignoring stopProcessing because scheduler is not running (state={})", state.get())
                }
            }

        fun cancelScheduledJob(cancel: () -> Unit) =
            lock.withLock {
                when (state.get()) {
                    State.IDLE -> {
                        cancel.invoke()
                        transitionTo(State.STOPPED, "scheduler cancelled while idle")
                    }

                    State.RUNNING -> {
                        cancel.invoke()
                        transitionTo(State.SHUTTING_DOWN, "scheduler cancelled while processing")
                        awaitProcessingComplete()
                        transitionTo(State.STOPPED, "shutdown finished")
                    }

                    State.SHUTTING_DOWN -> {
                        log.trace("Already shutting down. Awaiting processing completion")
                        awaitProcessingComplete()
                        transitionTo(State.STOPPED, "shutdown finished")
                    }

                    State.STOPPED -> {
                        log.trace("Ignoring cancel request because scheduler is already stopped")
                    }
                }
            }

        private fun awaitProcessingComplete() {
            log.debug("Waiting for processing cycle to complete (timeout={}s)", shutdownTimeoutSeconds)

            try {
                val completed = processingComplete.await(shutdownTimeoutSeconds, TimeUnit.SECONDS)
                if (completed) {
                    log.trace("Processing cycle completed while shutting down")
                } else {
                    log.warn("Shutdown timeout reached after {}s; forcing shutdown", shutdownTimeoutSeconds)
                }
            } catch (ex: InterruptedException) {
                Thread.currentThread().interrupt()
                log.warn("Interrupted while waiting for processing cycle completion; forcing shutdown", ex)
            }
        }

        private fun transitionTo(
            newState: State,
            reason: String,
        ) {
            val oldState = state.get()
            if (oldState != newState) {
                log.trace("BeanState transition: {} -> {} ({})", oldState, newState, reason)
                state.set(newState)
            }
        }
    }
}
