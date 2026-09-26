package io.namastack.outbox.runtime

import io.namastack.outbox.Outbox
import io.namastack.outbox.OutboxProcessingScheduler
import io.namastack.outbox.instance.OutboxInstanceRegistry
import io.namastack.outbox.partition.PartitionCoordinator

/**
 * One isolated outbox object graph with explicit programmatic lifecycle.
 *
 * Starting follows dependency order: instance registration and heartbeat, initial partition
 * coordination and finally record processing. Closing reverses that order
 * and then closes only persistence and threading resources declared as owned by the runtime.
 *
 * @property outbox Scheduling API backed by this runtime
 * @param instanceRegistry Instance registration and heartbeat lifecycle
 * @param partitionCoordinator Partition assignment coordinator
 * @param processingScheduler Record processing lifecycle
 * @param ownedPersistenceResources Persistence resources closed with this runtime
 * @param ownedThreadingResources Threading resources closed with this runtime
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
class OutboxRuntime internal constructor(
    val outbox: Outbox,
    private val instanceRegistry: OutboxInstanceRegistry,
    private val partitionCoordinator: PartitionCoordinator,
    private val processingScheduler: OutboxProcessingScheduler,
    ownedPersistenceResources: List<AutoCloseable>,
    ownedThreadingResources: List<AutoCloseable>,
) : AutoCloseable {
    private val persistenceResources = ownedPersistenceResources.toList()
    private val threadingResources = ownedThreadingResources.toList()

    private var started = false
    private var closed = false
    private var instanceStarted = false
    private var coordinatorStarted = false
    private var processingStarted = false

    /**
     * Starts this runtime once in dependency order.
     *
     * Repeated calls while running are ignored. If startup fails, every component started so far
     * is stopped and every owned resource is closed before the original failure is rethrown.
     *
     * @throws IllegalStateException if the runtime has already been closed
     * @throws Throwable if a runtime component fails during startup
     */
    @Synchronized
    fun start() {
        check(!closed) { "Outbox runtime is already closed" }
        if (started) return

        try {
            try {
                instanceRegistry.start()
            } finally {
                instanceStarted = instanceRegistry.isRunning
            }

            try {
                partitionCoordinator.start()
            } finally {
                coordinatorStarted = partitionCoordinator.isRunning
            }

            try {
                processingScheduler.start()
            } finally {
                processingStarted = processingScheduler.isRunning
            }

            started = true
        } catch (failure: Throwable) {
            closed = true
            cleanup(failure)
            throw failure
        }
    }

    /**
     * Returns whether the complete runtime startup sequence finished successfully.
     *
     * @return `true` after successful startup and before closing
     */
    @Synchronized
    fun isRunning(): Boolean = started && !closed

    /**
     * Stops this runtime and closes its owned resources once.
     *
     * Closing an assembled but not yet started runtime is supported. Repeated calls are ignored.
     * If cleanup fails, remaining cleanup steps still run and the first failure is rethrown with
     * later failures attached as suppressed exceptions.
     *
     * @throws Throwable if a lifecycle component or owned resource fails during cleanup
     */
    @Synchronized
    override fun close() {
        if (closed) return

        closed = true
        val failure = cleanup()
        if (failure != null) throw failure
    }

    private fun cleanup(initialFailure: Throwable? = null): Throwable? {
        var failure = initialFailure

        fun attempt(action: () -> Unit) {
            try {
                action()
            } catch (cleanupFailure: Throwable) {
                if (failure == null) {
                    failure = cleanupFailure
                } else if (failure !== cleanupFailure) {
                    checkNotNull(failure).addSuppressed(cleanupFailure)
                }
            }
        }

        if (processingStarted) {
            attempt(processingScheduler::stop)
            processingStarted = false
        }

        if (coordinatorStarted) {
            attempt(partitionCoordinator::stop)
            coordinatorStarted = false
        }

        if (instanceStarted) {
            attempt(instanceRegistry::stop)
            instanceStarted = false
        }

        threadingResources.asReversed().forEach { resource -> attempt(resource::close) }
        persistenceResources.asReversed().forEach { resource -> attempt(resource::close) }
        started = false

        return failure
    }
}
