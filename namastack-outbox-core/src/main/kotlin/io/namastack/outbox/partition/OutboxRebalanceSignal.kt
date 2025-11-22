package io.namastack.outbox.partition

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thread-safe signal to request a partition rebalance.
 *
 * Concurrency & semantics:
 * - [request] is idempotent and sets the flag to true.
 * - [consume] atomically returns the current flag and resets it to false (one-shot consumption).
 */
class OutboxRebalanceSignal {
    private val requested = AtomicBoolean(false)

    fun request() = requested.set(true)

    fun consume(): Boolean = requested.getAndSet(false)
}
