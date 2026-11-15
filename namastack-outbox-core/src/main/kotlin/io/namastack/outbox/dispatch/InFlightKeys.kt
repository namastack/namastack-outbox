package io.namastack.outbox.dispatch

import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Bounded set of record keys in flight during one continuous dispatch run.
 *
 * Each key holds one of [capacity] slots from [tryReserve] until [release]. The dispatching thread
 * reserves slots; key tasks release them from worker threads.
 *
 * @param capacity Maximum number of keys in flight
 *
 * @author Aleksander Zamojski
 * @since 2.0.0
 */
internal class InFlightKeys(
    private val capacity: Int,
) {
    private val slots: Semaphore = Semaphore(capacity)
    private val keys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Number of slots not held by a key. */
    val freeSlots: Int
        get() = slots.availablePermits()

    /**
     * Reserves a slot for [key] without blocking.
     *
     * @return `false` if no slot is free
     */
    fun tryReserve(key: String): Boolean {
        if (!slots.tryAcquire()) return false
        if (!keys.add(key)) {
            slots.release()
            error("Attempted to reserve key '$key' which is already in flight.")
        }
        return true
    }

    /**
     * Releases the slot of [key].
     *
     * @throws IllegalStateException if [key] is not in flight; one slot is freed anyway
     */
    fun release(key: String) {
        val removed = keys.remove(key)
        // The slot is freed even for an unknown key, so a failed check cannot leak a slot.
        slots.release()
        check(removed) { "Attempted to release key '$key' which is not in flight." }
    }

    /**
     * Waits until at least [count] slots are free.
     *
     * @return `false` if the timeout passed first
     * @throws InterruptedException if the waiting thread is interrupted
     */
    fun awaitFreeSlots(
        count: Int,
        timeout: Duration,
    ): Boolean {
        val acquired = slots.tryAcquire(count, timeout.toNanos(), TimeUnit.NANOSECONDS)
        if (acquired) {
            slots.release(count)
        }
        return acquired
    }

    /**
     * Waits until every slot is free.
     *
     * @return `false` if the timeout passed first
     * @throws InterruptedException if the waiting thread is interrupted
     */
    fun awaitIdle(timeout: Duration): Boolean = awaitFreeSlots(capacity, timeout)

    /** Waits, ignoring interrupts, until every slot is free. */
    fun awaitIdleUninterruptibly() {
        slots.acquireUninterruptibly(capacity)
        slots.release(capacity)
    }

    /** Reports whether no key is in flight. */
    fun isIdle(): Boolean = freeSlots == capacity

    /** Returns the keys in flight at the time of the call. */
    fun snapshot(): Set<String> = keys.toSet()
}
