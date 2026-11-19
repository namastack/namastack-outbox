package io.namastack.outbox

import io.micrometer.observation.ObservationRegistry
import io.namastack.outbox.dispatch.OutboxKeyDispatcher
import io.namastack.outbox.dispatch.OutboxKeyDispatcherFactory
import io.namastack.outbox.partition.PartitionCoordinator
import io.namastack.outbox.processor.OutboxRecordProcessorChainInvoker
import io.namastack.outbox.runtime.OutboxRuntimeSettings
import io.namastack.outbox.trigger.OutboxPollingTrigger
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.support.ScheduledMethodRunnable
import java.lang.reflect.Method
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Scheduler for processing outbox records with partition-aware distribution.
 *
 * Each run takes a stable partition assignment from the PartitionCoordinator, selects record keys
 * from the assigned partitions, and processes them on the processing executor. How keys are
 * selected within a run follows [OutboxRuntimeSettings.Polling.dispatchMode]. A run returns only
 * after every accepted key task has finished.
 *
 * Record processing is delegated to a chain of processors that handle the complete
 * record lifecycle (dispatch to handler, retry logic, fallback invocation, failure marking).
 *
 * @author Roland Beisel
 * @since 0.2.0
 */
@OpenForProxy
class OutboxProcessingScheduler internal constructor(
    private val trigger: OutboxPollingTrigger,
    private val taskScheduler: TaskScheduler,
    private val observationRegistry: () -> ObservationRegistry,
    private val partitionCoordinator: PartitionCoordinator,
    private val keyDispatcher: OutboxKeyDispatcher,
    private val settings: OutboxRuntimeSettings,
) : SmartLifecycle {
    /**
     * Creates a scheduler that dispatches record keys according to
     * [OutboxRuntimeSettings.Polling.dispatchMode].
     *
     * @param trigger Polling trigger that determines when the next processing run should occur
     * @param taskScheduler Spring TaskScheduler for scheduling the processing job
     * @param observationRegistry Supplier for obtaining the Micrometer [ObservationRegistry]
     * @param recordRepository Repository for loading record keys and records
     * @param recordProcessorChainInvoker Invoker for the instrumented record processor chain
     * @param partitionCoordinator Coordinator for partition assignments
     * @param taskExecutor Executor for parallel key processing
     * @param settings Effective runtime settings
     * @param clock Clock for time calculations
     */
    constructor(
        trigger: OutboxPollingTrigger,
        taskScheduler: TaskScheduler,
        observationRegistry: () -> ObservationRegistry,
        recordRepository: OutboxRecordRepository,
        recordProcessorChainInvoker: OutboxRecordProcessorChainInvoker,
        partitionCoordinator: PartitionCoordinator,
        taskExecutor: TaskExecutor,
        settings: OutboxRuntimeSettings,
        clock: Clock,
    ) : this(
        trigger = trigger,
        taskScheduler = taskScheduler,
        observationRegistry = observationRegistry,
        partitionCoordinator = partitionCoordinator,
        keyDispatcher =
            OutboxKeyDispatcherFactory.create(
                settings = settings,
                trigger = trigger,
                recordRepository = recordRepository,
                recordProcessorChainInvoker = recordProcessorChainInvoker,
                taskExecutor = taskExecutor,
                clock = clock,
            ),
        settings = settings,
    )

    companion object {
        const val SCHEDULER_NAME: String = "outboxDefaultScheduler"

        private val SCHEDULE_METHOD_NAME: String = (OutboxProcessingScheduler::process).name
        private val SCHEDULE_METHOD: Method = OutboxProcessingScheduler::class.java.getMethod(SCHEDULE_METHOD_NAME)
    }

    private val log = LoggerFactory.getLogger(OutboxProcessingScheduler::class.java)

    private val lifecycle = SchedulerLifecycle(settings.processing.shutdownTimeout)

    private var scheduledTask: ScheduledFuture<*>? = null

    /**
     * Starts after instance registration, partition coordination, and completion batching.
     *
     * @return phase 3; Spring stops this scheduler before the completion batcher
     */
    override fun getPhase(): Int = 3

    /**
     * Reports whether the processing job is scheduled.
     *
     * @return true from [start] until [stop]; false once [stop] has started, also while a cancelled run
     * finishes its accepted keys
     */
    override fun isRunning(): Boolean = lifecycle.isScheduled()

    /**
     * Registers the outbox processing job with the task scheduler.
     *
     * Automatically invoked after dependency injection. Schedules [process] to run
     * according to the configured [trigger]. Idempotent - duplicate registrations are ignored.
     *
     * @throws RuntimeException if registering the processing job fails
     */
    override fun start() {
        lifecycle.scheduleJob {
            val runnable = ScheduledMethodRunnable(this, SCHEDULE_METHOD, SCHEDULER_NAME, observationRegistry)
            scheduledTask = taskScheduler.schedule(runnable, trigger)
            log.info(
                "OutboxProcessingScheduler scheduled (trigger={}, dispatcher={})",
                trigger.javaClass.simpleName,
                keyDispatcher.javaClass.simpleName,
            )
        }
    }

    /**
     * Unregisters the outbox processing job and performs graceful shutdown.
     *
     * Automatically invoked during application shutdown. Cancels future scheduling and,
     * if a run is active, stops it from dispatching further keys and blocks until the
     * accepted keys finish, a timeout, or interruption
     * (up to [OutboxRuntimeSettings.Processing.shutdownTimeout]). A run that outlasts the
     * wait finishes its accepted keys without dispatching new ones, also after a restart;
     * no new run starts until it ends.
     *
     * @throws RuntimeException if cancelling the scheduled job fails
     */
    override fun stop() {
        log.info("Initiating OutboxProcessingScheduler shutdown...")
        lifecycle.shutdown {
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

    /**
     * Main processing run. Invoked by the scheduler according to the configured trigger.
     *
     * Lets the [OutboxKeyDispatcher] process record keys of the assigned partitions in parallel.
     * Each record is handled by the processor chain (handler → retry → fallback → failure).
     * A run that keeps dispatching ends when a partition rebalance is due or shutdown starts.
     * If the job is not scheduled, another run is active, or a compatibility cooldown is active,
     * the run is skipped.
     */
    fun process() {
        if (keyDispatcher.isCompatibilityCooldownActive()) return
        val run = lifecycle.startRun() ?: return

        try {
            partitionCoordinator.withStableAssignments { partitions ->
                if (partitions.isNotEmpty()) {
                    log.debug("Processing {} partitions: {}", partitions.size, partitions.sorted())
                    keyDispatcher.dispatch(partitions) {
                        partitionCoordinator.isRebalanceDue() || run.isCancelled
                    }
                }
            }
        } catch (ex: Exception) {
            log.error("Error during outbox processing", ex)
        } finally {
            lifecycle.finishRun(run)
        }
    }

    /**
     * Thread-safe lifecycle of [OutboxProcessingScheduler].
     *
     * It tracks two facts separately: whether the processing job is scheduled, and which processing
     * run is active. At most one run is active at a time. Shutdown cancels the active run, and a
     * cancelled run stays cancelled until it ends, even after the shutdown timeout or a restart.
     *
     * @param shutdownTimeout Maximum time to wait for an active run during shutdown
     */
    internal class SchedulerLifecycle(
        private val shutdownTimeout: Duration,
    ) {
        /**
         * One processing run. Once cancelled, it stays cancelled.
         */
        class Run internal constructor() {
            /** Whether shutdown asked this run to stop dispatching new keys. */
            @Volatile
            var isCancelled: Boolean = false
                private set

            internal fun cancel() {
                isCancelled = true
            }
        }

        private val log = LoggerFactory.getLogger(OutboxProcessingScheduler::class.java)

        private val lock = ReentrantLock()
        private val runFinished = lock.newCondition()

        @Volatile
        private var scheduled = false

        /** Guarded by [lock]; only the run's own [finishRun] clears it. */
        private var activeRun: Run? = null

        /**
         * Reports whether the processing job is scheduled.
         *
         * @return true from [scheduleJob] until [shutdown]
         */
        fun isScheduled(): Boolean = scheduled

        /**
         * Invokes [schedule] and marks the job as scheduled. Ignored if the job is already scheduled.
         *
         * A cancelled run that is still finishing stays cancelled; no new run starts until it ends.
         *
         * @param schedule Registers the processing job
         * @throws Exception if registering the processing job fails; the job stays unscheduled
         */
        fun scheduleJob(schedule: () -> Unit) =
            lock.withLock {
                if (scheduled) {
                    log.trace("Ignoring schedule request because the processing job is already scheduled")
                } else {
                    schedule.invoke()
                    scheduled = true
                    log.trace("Processing job scheduled")
                }
            }

        /**
         * Starts a processing run.
         *
         * @return the new run, or `null` if the job is not scheduled or another run is active
         */
        fun startRun(): Run? =
            lock.withLock {
                when {
                    !scheduled -> {
                        log.trace("Skipping processing run because the processing job is not scheduled")
                        null
                    }

                    activeRun != null -> {
                        log.trace("Skipping processing run because another run is active")
                        null
                    }

                    else -> Run().also { activeRun = it }
                }
            }

        /**
         * Records that [run] has ended and notifies threads waiting in [shutdown].
         *
         * Ignored if [run] is not the active run.
         *
         * @param run Run returned by [startRun]
         */
        fun finishRun(run: Run): Unit =
            lock.withLock {
                if (activeRun !== run) return@withLock
                activeRun = null
                runFinished.signalAll()
            }

        /**
         * Cancels the scheduled job, cancels the active run, and waits for that run to end.
         *
         * The wait ends when the run ends, after [shutdownTimeout], or on interruption. The job is
         * unscheduled in every case; a run still finishing after the wait stays cancelled.
         *
         * @param cancelJob Cancels the scheduled processing job when it is scheduled
         * @throws Exception if cancelling the scheduled job fails; nothing changes in that case
         */
        fun shutdown(cancelJob: () -> Unit) =
            lock.withLock {
                if (scheduled) {
                    cancelJob.invoke()
                    scheduled = false
                    log.trace("Processing job cancelled")
                }
                val run = activeRun ?: return@withLock
                run.cancel()
                awaitRunFinished(run)
            }

        private fun awaitRunFinished(run: Run) {
            log.debug("Waiting for processing run to complete (timeout={}ms)", shutdownTimeout.toMillis())

            var remainingNanos = shutdownTimeout.toNanos()
            try {
                while (activeRun === run && remainingNanos > 0) {
                    remainingNanos = runFinished.awaitNanos(remainingNanos)
                }
            } catch (ex: InterruptedException) {
                Thread.currentThread().interrupt()
                log.warn("Interrupted while waiting for processing run completion; forcing shutdown", ex)
                return
            }

            if (activeRun === run) {
                log.warn(
                    "Shutdown timeout reached after {}ms; the active run finishes its accepted keys without dispatching new ones",
                    shutdownTimeout.toMillis(),
                )
            } else {
                log.trace("Processing run completed while shutting down")
            }
        }
    }
}
