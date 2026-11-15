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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Scheduler for processing outbox records with partition-aware distribution.
 *
 * Each run takes a stable partition assignment from the PartitionCoordinator, selects record keys
 * from the assigned partitions, and processes them on the processing executor. How keys are
 * selected within a run follows [OutboxRuntimeSettings.Processing.dispatchMode]. A run returns only
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
     * [OutboxRuntimeSettings.Processing.dispatchMode].
     *
     * @param trigger Polling trigger that determines when the next processing cycle should occur
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

    private val lifecycle = SchedulerLifecycleStateMachine(settings.processing.shutdownTimeout)

    private var scheduledTask: ScheduledFuture<*>? = null

    /**
     * Starts after instance registration, partition coordination, and completion batching.
     *
     * @return phase 3; Spring stops this scheduler before the completion batcher
     */
    override fun getPhase(): Int = 3

    /**
     * Returns `true` while this lifecycle is active (idle, running, or shutting down).
     *
     * @return true while idle, processing, or shutting down; false when the lifecycle state is stopped
     */
    override fun isRunning(): Boolean = lifecycle.isStarted()

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
     * if a cycle is currently running, stops further dispatching and blocks until the
     * accepted keys finish, a timeout, or interruption
     * (up to [OutboxRuntimeSettings.Processing.shutdownTimeout]).
     *
     * @throws RuntimeException if cancelling the scheduled job fails
     */
    override fun stop() {
        log.info("Initiating OutboxProcessingScheduler shutdown...")
        lifecycle.cancelScheduledJob {
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
     * Main processing cycle. Invoked by the scheduler according to the configured trigger.
     *
     * Lets the [OutboxKeyDispatcher] process record keys of the assigned partitions in parallel.
     * Each record is handled by the processor chain (handler → retry → fallback → failure).
     * A run that keeps dispatching ends when a partition rebalance is due or shutdown starts.
     * If the scheduler is not idle or a compatibility cooldown is active, the cycle is skipped.
     */
    fun process() {
        if (keyDispatcher.isCompatibilityCooldownActive()) return
        if (!lifecycle.startProcessing()) return

        try {
            partitionCoordinator.withStableAssignments { partitions ->
                if (partitions.isNotEmpty()) {
                    log.debug("Processing {} partitions: {}", partitions.size, partitions.sorted())
                    keyDispatcher.dispatch(partitions) {
                        partitionCoordinator.isRebalanceDue() || lifecycle.isStopping()
                    }
                }
            }
        } catch (ex: Exception) {
            log.error("Error during outbox processing", ex)
        } finally {
            lifecycle.stopProcessing()
        }
    }

    /**
     * Thread-safe lifecycle state machine used by [OutboxProcessingScheduler].
     *
     * It coordinates job registration, processing execution, and graceful shutdown,
     * including waiting for an in-flight cycle to complete with a bounded timeout.
     *
     * @param shutdownTimeout Maximum time to wait for an active cycle during shutdown
     */
    class SchedulerLifecycleStateMachine(
        private val shutdownTimeout: Duration,
    ) {
        /**
         * Lifecycle states for scheduler registration and processing execution.
         */
        enum class LifecycleState {
            /** Scheduler is not registered and not running any work. */
            STOPPED,

            /** Scheduler is registered and waiting for the next trigger. */
            IDLE,

            /** A processing cycle is currently running. */
            RUNNING,

            /** Shutdown is in progress and waiting for an active cycle to finish. */
            SHUTTING_DOWN,
        }

        private val log = LoggerFactory.getLogger(OutboxProcessingScheduler::class.java)

        private val lock = ReentrantLock()
        private val processingComplete = lock.newCondition()
        private val state = AtomicReference(LifecycleState.STOPPED)

        /**
         * Reports whether the lifecycle state is other than [LifecycleState.STOPPED].
         *
         * @return true while idle, processing, or shutting down; false when the state is stopped
         */
        fun isStarted(): Boolean = state.get() != LifecycleState.STOPPED

        /**
         * Reports whether shutdown was requested while a processing cycle is running.
         *
         * @return true in [LifecycleState.SHUTTING_DOWN]
         */
        fun isStopping(): Boolean = state.get() == LifecycleState.SHUTTING_DOWN

        /**
         * Transitions from [LifecycleState.STOPPED] to [LifecycleState.IDLE] and invokes [schedule].
         *
         * If already started, the request is ignored.
         *
         * @param schedule Registers the processing job when the lifecycle is stopped
         * @throws Exception if registering the processing job fails; the lifecycle remains stopped
         */
        fun scheduleJob(schedule: () -> Unit) =
            lock.withLock {
                if (state.get() == LifecycleState.STOPPED) {
                    schedule.invoke()
                    transitionTo(LifecycleState.IDLE, "scheduler registered")
                } else {
                    log.trace(
                        "Ignoring schedule request because scheduler is already active (state={})",
                        state.get(),
                    )
                }
            }

        /**
         * Marks the beginning of a processing cycle.
         *
         * @return `true` only when transition from [LifecycleState.IDLE] to [LifecycleState.RUNNING] succeeds
         */
        fun startProcessing(): Boolean =
            lock.withLock {
                return if (state.get() == LifecycleState.IDLE) {
                    transitionTo(LifecycleState.RUNNING, "processing cycle started")
                    true
                } else {
                    log.trace(
                        "Skipping processing cycle because scheduler is not idle (state={})",
                        state.get(),
                    )
                    false
                }
            }

        /**
         * Marks processing completion.
         *
         * In [LifecycleState.SHUTTING_DOWN], notifies waiting shutdown threads instead of returning to idle.
         */
        fun stopProcessing(): Unit =
            lock.withLock {
                if (state.get() == LifecycleState.RUNNING) {
                    transitionTo(LifecycleState.IDLE, "processing cycle finished")
                } else if (state.get() == LifecycleState.SHUTTING_DOWN) {
                    log.trace("Processing cycle finished during shutdown. Notifying waiting thread")
                    processingComplete.signalAll()
                } else {
                    log.trace(
                        "Ignoring stopProcessing because scheduler is not running (state={})",
                        state.get(),
                    )
                }
            }

        /**
         * Cancels scheduling and transitions the lifecycle toward [LifecycleState.STOPPED].
         *
         * When called during [LifecycleState.RUNNING] or [LifecycleState.SHUTTING_DOWN], this method waits
         * for processing completion, timeout, or interruption before finalizing stop.
         *
         * @param cancel Cancels the scheduled processing job when the lifecycle is idle or running
         * @throws Exception if cancelling the scheduled job fails before shutdown can proceed
         */
        fun cancelScheduledJob(cancel: () -> Unit) =
            lock.withLock {
                when (state.get()) {
                    LifecycleState.IDLE -> {
                        cancel.invoke()
                        transitionTo(LifecycleState.STOPPED, "scheduler cancelled while idle")
                    }

                    LifecycleState.RUNNING -> {
                        cancel.invoke()
                        transitionTo(LifecycleState.SHUTTING_DOWN, "scheduler cancelled while processing")
                        awaitProcessingComplete()
                        transitionTo(LifecycleState.STOPPED, "shutdown finished")
                    }

                    LifecycleState.SHUTTING_DOWN -> {
                        log.trace("Already shutting down. Awaiting processing completion")
                        awaitProcessingComplete()
                        transitionTo(LifecycleState.STOPPED, "shutdown finished")
                    }

                    LifecycleState.STOPPED -> {
                        log.trace("Ignoring cancel request because scheduler is already stopped")
                    }
                }
            }

        private fun awaitProcessingComplete() {
            log.debug("Waiting for processing cycle to complete (timeout={}ms)", shutdownTimeout.toMillis())

            try {
                val completed = processingComplete.await(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)
                if (completed) {
                    log.trace("Processing cycle completed while shutting down")
                } else {
                    log.warn("Shutdown timeout reached after {}ms; forcing shutdown", shutdownTimeout.toMillis())
                }
            } catch (ex: InterruptedException) {
                Thread.currentThread().interrupt()
                log.warn("Interrupted while waiting for processing cycle completion; forcing shutdown", ex)
            }
        }

        private fun transitionTo(
            newState: LifecycleState,
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
