package io.namastack.outbox.partition

import org.springframework.scheduling.annotation.Scheduled

class PartitionRebalanceScheduler(
    private val rebalanceSignal: OutboxRebalanceSignal,
) {
    @Scheduled(initialDelayString = "0", fixedDelayString = $$"${outbox.rebalance-interval:5000}")
    fun requestRebalance() {
        rebalanceSignal.request()
    }
}
