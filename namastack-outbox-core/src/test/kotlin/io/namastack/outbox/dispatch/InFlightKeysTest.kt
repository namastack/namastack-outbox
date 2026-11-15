package io.namastack.outbox.dispatch

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS

class InFlightKeysTest {
    private val inFlightKeys = InFlightKeys(capacity = 3)

    @Nested
    @DisplayName("Reservation")
    inner class Reservation {
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
    }

    @Nested
    @DisplayName("Waiting")
    inner class Waiting {
        @Test
        fun `awaitFreeSlots waits until the requested number of slots is free`() {
            inFlightKeys.tryReserve("A")
            inFlightKeys.tryReserve("B")
            inFlightKeys.tryReserve("C")

            assertThat(inFlightKeys.awaitFreeSlots(2, Duration.ofMillis(10))).isFalse()

            inFlightKeys.release("A")
            inFlightKeys.release("B")
            assertThat(inFlightKeys.awaitFreeSlots(2, Duration.ofMillis(10))).isTrue()
            assertThat(inFlightKeys.freeSlots).isEqualTo(2)
        }

        @Test
        fun `awaitFreeSlots returns as soon as another thread releases a slot`() {
            inFlightKeys.tryReserve("A")
            inFlightKeys.tryReserve("B")
            inFlightKeys.tryReserve("C")
            val executor = Executors.newSingleThreadExecutor()
            try {
                val waiting = executor.submit<Boolean> { inFlightKeys.awaitFreeSlots(1, Duration.ofMinutes(1)) }
                await().during(Duration.ofMillis(100)).atMost(5, SECONDS).until { !waiting.isDone }

                inFlightKeys.release("B")

                assertThat(waiting.get(5, SECONDS)).isTrue()
                assertThat(inFlightKeys.freeSlots).isEqualTo(1)
                assertThat(inFlightKeys.snapshot()).containsExactlyInAnyOrder("A", "C")
            } finally {
                executor.shutdownNow()
            }
        }

        @Test
        fun `awaitIdle waits for every slot and not only for the reserved count`() {
            inFlightKeys.tryReserve("A")

            assertThat(inFlightKeys.awaitIdle(Duration.ofMillis(10))).isFalse()

            inFlightKeys.release("A")
            assertThat(inFlightKeys.awaitIdle(Duration.ofMillis(10))).isTrue()
            assertThat(inFlightKeys.isIdle()).isTrue()
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

                await().during(Duration.ofMillis(100)).atMost(5, SECONDS).until { !waiting.isDone }

                inFlightKeys.release("A")
                assertThat(waiting.get(5, SECONDS)).describedAs("interrupt flag kept").isTrue()
            } finally {
                executor.shutdownNow()
            }
        }

        @Test
        fun `interruptible waits throw when interrupted and keep the slots`() {
            inFlightKeys.tryReserve("A")
            val executor = Executors.newSingleThreadExecutor()
            try {
                val waitingForSlots =
                    executor.submit<Boolean> {
                        Thread.currentThread().interrupt()
                        inFlightKeys.awaitFreeSlots(3, Duration.ofMinutes(1))
                    }
                assertThatThrownBy { waitingForSlots.get(5, SECONDS) }
                    .isInstanceOf(ExecutionException::class.java)
                    .hasCauseInstanceOf(InterruptedException::class.java)

                val waitingForIdle =
                    executor.submit<Boolean> {
                        Thread.currentThread().interrupt()
                        inFlightKeys.awaitIdle(Duration.ofMinutes(1))
                    }
                assertThatThrownBy { waitingForIdle.get(5, SECONDS) }
                    .isInstanceOf(ExecutionException::class.java)
                    .hasCauseInstanceOf(InterruptedException::class.java)

                assertThat(inFlightKeys.freeSlots).isEqualTo(2)
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
