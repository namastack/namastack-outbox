package io.namastack.outbox.partition

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PartitionRebalanceSchedulerTest {
    @Test
    fun `should set and reset signal`() {
        val rebalanceSignal = OutboxRebalanceSignal()
        val rebalanceScheduler = PartitionRebalanceScheduler(rebalanceSignal)

        rebalanceScheduler.requestRebalance()

        assertThat(rebalanceSignal.consume()).isTrue
    }
}
