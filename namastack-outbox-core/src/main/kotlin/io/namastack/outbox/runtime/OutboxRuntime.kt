package io.namastack.outbox.runtime

import io.namastack.outbox.Outbox
import org.springframework.context.Lifecycle

/**
 * One isolated outbox graph with explicit lifecycle.
 *
 * A newly created runtime is stopped. [start] starts its components in dependency order and
 * [close] stops them in reverse order. Closing the runtime stops scheduled work but does not close
 * repositories, executors, or schedulers supplied through its specification.
 *
 * @property outbox Scheduling API backed by this runtime
 * @param lifecycleComponents Runtime components in startup order
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
class OutboxRuntime internal constructor(
    val outbox: Outbox,
    lifecycleComponents: List<Lifecycle>,
) : AutoCloseable {
    private val lifecycleComponents = lifecycleComponents.toList()
    private val startedComponents = mutableListOf<Lifecycle>()

    private var started = false
    private var closed = false

    /**
     * Starts this runtime once in dependency order.
     *
     * Repeated calls while the runtime is running are ignored. If startup fails, components that
     * started successfully are stopped before the failure is rethrown.
     *
     * @throws IllegalStateException if the runtime has already been closed
     */
    @Synchronized
    fun start() {
        check(!closed) { "Outbox runtime is already closed" }
        if (started) return

        try {
            lifecycleComponents.forEach { component ->
                try {
                    component.start()
                    startedComponents += component
                } catch (failure: Throwable) {
                    if (component.isRunning) startedComponents += component
                    throw failure
                }
            }
            started = true
        } catch (failure: Throwable) {
            closed = true
            stopStartedComponents()
            throw failure
        }
    }

    /**
     * Returns whether this runtime and all of its lifecycle components are running.
     *
     * @return `true` after successful startup and before closing
     */
    @Synchronized
    fun isRunning(): Boolean =
        started &&
            !closed &&
            lifecycleComponents.all(Lifecycle::isRunning)

    /**
     * Stops this runtime once in reverse dependency order.
     *
     * Repeated calls are ignored. If stopping a component fails, the remaining components are still
     * stopped and the first failure is rethrown.
     *
     * @throws Throwable if a lifecycle component fails while stopping
     */
    @Synchronized
    override fun close() {
        if (closed) return

        closed = true
        val failure = stopStartedComponents()
        if (failure != null) throw failure
    }

    private fun stopStartedComponents(): Throwable? {
        var failure: Throwable? = null

        startedComponents.asReversed().forEach { component ->
            try {
                component.stop()
            } catch (stopFailure: Throwable) {
                if (failure == null) failure = stopFailure
            }
        }

        startedComponents.clear()
        started = false
        return failure
    }
}
