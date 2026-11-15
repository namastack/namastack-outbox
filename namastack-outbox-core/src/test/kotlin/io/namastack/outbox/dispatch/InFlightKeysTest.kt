package io.namastack.outbox.dispatch

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS

class InFlightKeysTest {
    private val inFlightKeys = InFlightKeys(capacity = 3)

    @Test
    fun `does not reserve more slots than the limit`() {
        assertThat(inFlightKeys.tryReserve("A")).isTrue()
        assertThat(inFlightKeys.tryReserve("B")).isTrue()
        assertThat(inFlightKeys.tryReserve("C")).isTrue()

        assertThat(inFlightKeys.tryReserve("D")).isFalse()
        assertThat(inFlightKeys.freeSlots).isZero()
        assertThat(inFlightKeys.snapshot()).containsExactlyInAnyOrder("A", "B", "C")
    }

    @Test
    fun `rejects reserving a key twice without losing a slot`() {
        inFlightKeys.tryReserve("A")

        assertThatThrownBy { inFlightKeys.tryReserve("A") }.isInstanceOf(IllegalStateException::class.java)
        assertThat(inFlightKeys.freeSlots).isEqualTo(2)
    }

    @Test
    fun `frees the slot when releasing an unknown key`() {
        inFlightKeys.tryReserve("A")

        assertThatThrownBy { inFlightKeys.release("B") }.isInstanceOf(IllegalStateException::class.java)
        assertThat(inFlightKeys.freeSlots).isEqualTo(3)
    }

    @Test
    fun `awaitIdle waits for every slot and not only for the reserved count`() {
        inFlightKeys.tryReserve("A")

        assertThat(inFlightKeys.awaitIdle(Duration.ofMillis(50))).isFalse()

        inFlightKeys.release("A")
        assertThat(inFlightKeys.awaitIdle(Duration.ofMillis(50))).isTrue()
        assertThat(inFlightKeys.isIdle()).isTrue()
    }

    @Test
    fun `awaitFreeSlots waits until the requested number of slots is free`() {
        inFlightKeys.tryReserve("A")
        inFlightKeys.tryReserve("B")
        inFlightKeys.tryReserve("C")

        assertThat(inFlightKeys.awaitFreeSlots(2, Duration.ofMillis(50))).isFalse()

        inFlightKeys.release("A")
        inFlightKeys.release("B")
        assertThat(inFlightKeys.awaitFreeSlots(2, Duration.ofMillis(50))).isTrue()
        assertThat(inFlightKeys.freeSlots).isEqualTo(2)
    }

    @Test
    fun `awaitIdleUninterruptibly ignores interrupts until every slot is free`() {
        inFlightKeys.tryReserve("A")
        val executor = Executors.newSingleThreadExecutor()
        try {
            val waiting =
                executor.submit<Boolean> {
                    Thread.currentThread().interrupt()
                    inFlightKeys.awaitIdleUninterruptibly()
                    Thread.interrupted()
                }

            Thread.sleep(100)
            assertThat(waiting.isDone).isFalse()

            inFlightKeys.release("A")
            assertThat(waiting.get(5, SECONDS)).describedAs("interrupt flag kept").isTrue()
        } finally {
            executor.shutdownNow()
        }
    }
}
