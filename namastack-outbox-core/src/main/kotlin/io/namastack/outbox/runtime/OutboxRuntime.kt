package io.namastack.outbox.runtime

import io.namastack.outbox.Outbox
import org.springframework.context.Lifecycle

/**
 * One isolated outbox graph with explicit lifecycle.
 *
 * A newly created runtime is stopped. [start] starts its components in dependency order and
 * [close] stops them in reverse order. Supplied persistence and threading resources remain owned
 * by the application.
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

    /** Starts this runtime once in dependency order. */
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

    /** Returns whether this runtime and all of its lifecycle components are running. */
    @Synchronized
    fun isRunning(): Boolean =
        started &&
            !closed &&
            lifecycleComponents.all(Lifecycle::isRunning)

    /** Stops this runtime once in reverse dependency order. */
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
