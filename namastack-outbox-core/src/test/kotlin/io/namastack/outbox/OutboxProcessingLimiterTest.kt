package io.namastack.outbox

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@DisplayName("OutboxProcessingLimiter")
class OutboxProcessingLimiterTest {
    @Test
    fun `acquire and release allows processing up to limit concurrently`() {
        val limit = 4
        val limiter = OutboxProcessingLimiter(limit)
        val schedulerExecutor = Executors.newFixedThreadPool(1)
        val taskExecutor = Executors.newFixedThreadPool(4)
        val counter = AtomicInteger(0)

        schedulerExecutor.execute {
            repeat(20) { cycle ->
                val id = cycle.toString()
                limiter.acquire(id)
                taskExecutor.execute {
                    try {
                        assertThat(counter.incrementAndGet()).isLessThanOrEqualTo(limit)
                        Thread.sleep(50)
                    } finally {
                        counter.decrementAndGet()
                        limiter.release(id)
                    }
                }
            }
        }

        await()
            .atMost(2, TimeUnit.SECONDS)
            .untilAsserted {
                assertThat(counter.get()).isEqualTo(0)
            }
    }

    @Test
    fun `release throws on unknown id`() {
        val limiter = OutboxProcessingLimiter(4)

        assertThatThrownBy {
            limiter.release("a")
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Attempted to release ID 'a' which is not currently being processed.")
    }

    @Test
    fun `awaitAll blocks until all permits are acquired`() {
        val limit = 4
        val limiter = OutboxProcessingLimiter(limit)
        val schedulerExecutor = Executors.newFixedThreadPool(1)
        val taskExecutor = Executors.newFixedThreadPool(4)
        val finishFlag = AtomicBoolean(false)

        schedulerExecutor.execute {
            repeat(4) { cycle ->
                val id = cycle.toString()
                limiter.acquire(id)
                taskExecutor.execute {
                    try {
                        Thread.sleep(100)
                    } finally {
                        limiter.release(id)
                    }
                }
            }

            limiter.awaitAll()

            assertThat(limiter.getUnprocessedIds()).isEmpty()
            finishFlag.set(true)
        }

        await()
            .atMost(2, TimeUnit.SECONDS)
            .untilAsserted {
                assertThat(finishFlag.get()).isTrue()
            }
    }

    @Test
    fun `awaitAll blocks until all permits are acquired2`() {
        val limiter = OutboxProcessingLimiter(2)
        limiter.acquire("x")
        limiter.acquire("y")
        assertTimeoutPreemptively(Duration.ofMillis(200)) {
            Thread {
                Thread.sleep(100)
                limiter.release("x")
                limiter.release("y")
            }.start()
            limiter.awaitAll()
        }
    }

    @Test
    fun `getUnprocessedIds returns empty when no task was processed`() {
        val limiter = OutboxProcessingLimiter(4)

        assertThat(limiter.getUnprocessedIds()).isEmpty()
    }

    @Test
    fun `getUnprocessedIds returns id when task is being processed`() {
        val limiter = OutboxProcessingLimiter(4)
        limiter.acquire("a")

        assertThat(limiter.getUnprocessedIds()).containsExactly("a")
    }

    @Test
    fun `getUnprocessedIds returns empty when task was processed`() {
        val limiter = OutboxProcessingLimiter(4)
        limiter.acquire("a")
        limiter.release("a")

        assertThat(limiter.getUnprocessedIds()).isEmpty()
    }

    @Test
    fun `getUnprocessedIds returns ids when task are being processed`() {
        val limiter = OutboxProcessingLimiter(4)
        limiter.acquire("a")
        limiter.acquire("b")

        assertThat(limiter.getUnprocessedIds()).containsExactly("a", "b")
    }

    @Test
    fun `getUnprocessedIds returns one of two id when one task was processed`() {
        val limiter = OutboxProcessingLimiter(4)
        limiter.acquire("a")
        limiter.acquire("b")
        limiter.release("a")

        assertThat(limiter.getUnprocessedIds()).containsExactly("b")
    }

    @Test
    fun `getUnprocessedIds returns empty when tasks were processed`() {
        val limiter = OutboxProcessingLimiter(4)
        limiter.acquire("a")
        limiter.acquire("b")
        limiter.release("a")
        limiter.release("b")

        assertThat(limiter.getUnprocessedIds()).isEmpty()
    }

    @Test
    fun `getProcessedCount returns 0 when no task was processed`() {
        val limiter = OutboxProcessingLimiter(4)

        assertThat(limiter.getProcessedCount()).isEqualTo(0)
    }

    @Test
    fun `getProcessedCount returns 1 when one task was processed`() {
        val limiter = OutboxProcessingLimiter(4)
        limiter.acquire("a")
        limiter.release("a")

        assertThat(limiter.getProcessedCount()).isEqualTo(1)
    }

    @Test
    fun `getProcessedCount returns 2 when two tasks were processed`() {
        val limiter = OutboxProcessingLimiter(4)
        limiter.acquire("a")
        limiter.acquire("b")
        limiter.release("a")
        limiter.release("b")

        assertThat(limiter.getProcessedCount()).isEqualTo(2)
    }
}
