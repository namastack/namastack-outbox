package io.namastack.outbox.retry

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class JitteredRetryPolicyTest {
    @Test
    fun `shouldRetry delegates to base policy`() {
        val basePolicy = mockk<OutboxRetryPolicy>()
        val jitteredPolicy = JitteredRetryPolicy(basePolicy, Duration.ofSeconds(1))
        val exception = RuntimeException("Test exception")

        every { basePolicy.shouldRetry(exception) } returns true

        val result = jitteredPolicy.shouldRetry(exception)

        assertThat(result).isTrue()
        verify { basePolicy.shouldRetry(exception) }
    }

    @Test
    fun `shouldRetry delegates false result from base policy`() {
        val basePolicy = mockk<OutboxRetryPolicy>()
        val jitteredPolicy = JitteredRetryPolicy(basePolicy, Duration.ofSeconds(1))
        val exception = RuntimeException("Test exception")

        every { basePolicy.shouldRetry(exception) } returns false

        val result = jitteredPolicy.shouldRetry(exception)

        assertThat(result).isFalse()
        verify { basePolicy.shouldRetry(exception) }
    }

    @Test
    fun `maxRetries delegates to base policy`() {
        val basePolicy = mockk<OutboxRetryPolicy>()
        val jitteredPolicy = JitteredRetryPolicy(basePolicy, Duration.ofSeconds(1))

        every { basePolicy.maxRetries() } returns 5

        val result = jitteredPolicy.maxRetries()

        assertThat(result).isEqualTo(5)
        verify { basePolicy.maxRetries() }
    }

    @Test
    fun `nextDelay adds jitter to base delay`() {
        val basePolicy = mockk<OutboxRetryPolicy>()
        val baseDelay = Duration.ofSeconds(5)
        val jitter = Duration.ofSeconds(2)
        val jitteredPolicy = JitteredRetryPolicy(basePolicy, jitter)

        every { basePolicy.nextDelay(1) } returns baseDelay

        val result = jitteredPolicy.nextDelay(1)

        verify { basePolicy.nextDelay(1) }

        assertThat(result).isBetween(baseDelay, baseDelay.plus(jitter))
    }

    @Test
    fun `nextDelay with zero jitter returns base delay`() {
        val basePolicy = mockk<OutboxRetryPolicy>()
        val baseDelay = Duration.ofSeconds(3)
        val jitteredPolicy = JitteredRetryPolicy(basePolicy, Duration.ZERO)

        every { basePolicy.nextDelay(2) } returns baseDelay

        val result = jitteredPolicy.nextDelay(2)

        verify { basePolicy.nextDelay(2) }
        assertThat(result).isEqualTo(baseDelay)
    }

    @Test
    fun `nextDelay adds random jitter consistently within range`() {
        val basePolicy = mockk<OutboxRetryPolicy>()
        val baseDelay = Duration.ofMillis(1000)
        val jitter = Duration.ofMillis(500)
        val jitteredPolicy = JitteredRetryPolicy(basePolicy, jitter)

        every { basePolicy.nextDelay(any()) } returns baseDelay

        repeat(10) {
            val result = jitteredPolicy.nextDelay(1)
            assertThat(result).isBetween(baseDelay, baseDelay.plus(jitter))
        }

        verify(exactly = 10) { basePolicy.nextDelay(1) }
    }

    @Test
    fun `nextDelay works with different retry counts`() {
        val basePolicy = mockk<OutboxRetryPolicy>()
        val jitter = Duration.ofMillis(200)
        val jitteredPolicy = JitteredRetryPolicy(basePolicy, jitter)

        every { basePolicy.nextDelay(0) } returns Duration.ofSeconds(1)
        every { basePolicy.nextDelay(1) } returns Duration.ofSeconds(2)
        every { basePolicy.nextDelay(5) } returns Duration.ofSeconds(10)

        val result0 = jitteredPolicy.nextDelay(0)
        val result1 = jitteredPolicy.nextDelay(1)
        val result5 = jitteredPolicy.nextDelay(5)

        assertThat(result0).isBetween(Duration.ofSeconds(1), Duration.ofSeconds(1).plus(jitter))
        assertThat(result1).isBetween(Duration.ofSeconds(2), Duration.ofSeconds(2).plus(jitter))
        assertThat(result5).isBetween(Duration.ofSeconds(10), Duration.ofSeconds(10).plus(jitter))

        verify { basePolicy.nextDelay(0) }
        verify { basePolicy.nextDelay(1) }
        verify { basePolicy.nextDelay(5) }
    }

    @Test
    fun `nextDelay works with very small jitter`() {
        val basePolicy = mockk<OutboxRetryPolicy>()
        val baseDelay = Duration.ofSeconds(5)
        val smallJitter = Duration.ofMillis(1)
        val jitteredPolicy = JitteredRetryPolicy(basePolicy, smallJitter)

        every { basePolicy.nextDelay(1) } returns baseDelay

        val result = jitteredPolicy.nextDelay(1)

        assertThat(result).isBetween(baseDelay, baseDelay.plus(smallJitter))
    }

    @Test
    fun `nextDelay works with large jitter`() {
        val basePolicy = mockk<OutboxRetryPolicy>()
        val baseDelay = Duration.ofSeconds(1)
        val largeJitter = Duration.ofMinutes(5)
        val jitteredPolicy = JitteredRetryPolicy(basePolicy, largeJitter)

        every { basePolicy.nextDelay(3) } returns baseDelay

        val result = jitteredPolicy.nextDelay(3)

        assertThat(result).isBetween(baseDelay, baseDelay.plus(largeJitter))
    }

    @Test
    fun `nextDelay works with zero base delay`() {
        val basePolicy = mockk<OutboxRetryPolicy>()
        val jitter = Duration.ofMillis(100)
        val jitteredPolicy = JitteredRetryPolicy(basePolicy, jitter)

        every { basePolicy.nextDelay(0) } returns Duration.ZERO

        val result = jitteredPolicy.nextDelay(0)

        assertThat(result).isBetween(Duration.ZERO, jitter)
    }

    @Test
    fun `jitter distribution is within expected range over multiple calls`() {
        val basePolicy = mockk<OutboxRetryPolicy>()
        val baseDelay = Duration.ofSeconds(2)
        val jitter = Duration.ofSeconds(1)
        val jitteredPolicy = JitteredRetryPolicy(basePolicy, jitter)

        every { basePolicy.nextDelay(any()) } returns baseDelay

        val results = mutableListOf<Duration>()
        repeat(100) {
            results.add(jitteredPolicy.nextDelay(1))
        }

        results.forEach { result ->
            assertThat(result).isBetween(baseDelay, baseDelay.plus(jitter))
        }

        val minResult = results.minOrNull()!!
        val maxResult = results.maxOrNull()!!

        assertThat(maxResult).isGreaterThan(minResult)
    }

    @Test
    fun `integration test with real retry policies`() {
        val basePolicy =
            FixedDelayRetryPolicy(
                delay = Duration.ofSeconds(3),
                maxRetries = 5,
            )
        val jitter = Duration.ofMillis(500)
        val jitteredPolicy = JitteredRetryPolicy(basePolicy, jitter)

        assertThat(jitteredPolicy.shouldRetry(RuntimeException())).isTrue()
        assertThat(jitteredPolicy.shouldRetry(IllegalStateException())).isTrue()

        val result = jitteredPolicy.nextDelay(5)
        assertThat(result).isBetween(Duration.ofSeconds(3), Duration.ofSeconds(3).plus(jitter))
    }

    @Test
    fun `integration test with exponential backoff policy`() {
        val basePolicy =
            ExponentialBackoffRetryPolicy(
                initialDelay = Duration.ofSeconds(1),
                maxDelay = Duration.ofMinutes(5),
                backoffMultiplier = 2.0,
                maxRetries = 5,
            )
        val jitter = Duration.ofMillis(200)
        val jitteredPolicy = JitteredRetryPolicy(basePolicy, jitter)

        assertThat(jitteredPolicy.shouldRetry(RuntimeException())).isTrue()

        val result1 = jitteredPolicy.nextDelay(1)
        val result2 = jitteredPolicy.nextDelay(2)
        val result3 = jitteredPolicy.nextDelay(3)

        assertThat(result1).isBetween(Duration.ofSeconds(1), Duration.ofSeconds(1).plus(jitter))
        assertThat(result2).isBetween(Duration.ofSeconds(2), Duration.ofSeconds(2).plus(jitter))
        assertThat(result3).isBetween(Duration.ofSeconds(4), Duration.ofSeconds(4).plus(jitter))
    }
}
