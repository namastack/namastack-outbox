package io.namastack.outbox.partition

import org.springframework.scheduling.annotation.Scheduled

/**
 * Periodic trigger that requests a partition rebalance.
 *
 * It does NOT perform rebalancing itself; it only sets the signal.
 * The actual rebalance is consumed and executed after the next processing batch
 * in `OutboxProcessingScheduler` to ensure no overlap with record handling.
 */
class PartitionRebalanceScheduler(
    private val rebalanceSignal: OutboxRebalanceSignal,
) {
    /**
     * Periodically request a deferred rebalance.
     * Executed with a fixed delay; the signal is idempotent until consumed.
     */
    @Scheduled(
        initialDelayString = "0",
        fixedDelayString = $$"${outbox.rebalance-interval:10000}",
        scheduler = "heartbeatScheduler",
    )
    fun requestRebalance() {
        rebalanceSignal.request()
    }
}
