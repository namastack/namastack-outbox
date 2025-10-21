package io.namastack.outbox.retry

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class FixedDelayRetryPolicyTest {
    @Test
    fun `shouldRetry always returns true`() {
        val policy = FixedDelayRetryPolicy(Duration.ofSeconds(1))

        assertThat(policy.shouldRetry(RuntimeException("Test exception"))).isTrue()
        assertThat(policy.shouldRetry(IllegalStateException("Another exception"))).isTrue()
        assertThat(policy.shouldRetry(Exception("Generic exception"))).isTrue()
    }

    @Test
    fun `nextDelay returns fixed delay regardless of retry count`() {
        val fixedDelay = Duration.ofSeconds(5)
        val policy = FixedDelayRetryPolicy(fixedDelay)

        assertThat(policy.nextDelay(0)).isEqualTo(fixedDelay)
        assertThat(policy.nextDelay(1)).isEqualTo(fixedDelay)
        assertThat(policy.nextDelay(5)).isEqualTo(fixedDelay)
        assertThat(policy.nextDelay(100)).isEqualTo(fixedDelay)
    }

    @Test
    fun `nextDelay works with different time units`() {
        val millisDelay = Duration.ofMillis(500)
        val millisPolicy = FixedDelayRetryPolicy(millisDelay)

        assertThat(millisPolicy.nextDelay(0)).isEqualTo(millisDelay)
        assertThat(millisPolicy.nextDelay(10)).isEqualTo(millisDelay)

        val minutesDelay = Duration.ofMinutes(2)
        val minutesPolicy = FixedDelayRetryPolicy(minutesDelay)

        assertThat(minutesPolicy.nextDelay(0)).isEqualTo(minutesDelay)
        assertThat(minutesPolicy.nextDelay(10)).isEqualTo(minutesDelay)
    }

    @Test
    fun `nextDelay works with zero delay`() {
        val policy = FixedDelayRetryPolicy(Duration.ZERO)

        assertThat(policy.nextDelay(0)).isEqualTo(Duration.ZERO)
        assertThat(policy.nextDelay(1)).isEqualTo(Duration.ZERO)
        assertThat(policy.nextDelay(100)).isEqualTo(Duration.ZERO)
    }

    @Test
    fun `nextDelay works with very small delay`() {
        val tinyDelay = Duration.ofNanos(1)
        val policy = FixedDelayRetryPolicy(tinyDelay)

        assertThat(policy.nextDelay(0)).isEqualTo(tinyDelay)
        assertThat(policy.nextDelay(50)).isEqualTo(tinyDelay)
    }

    @Test
    fun `nextDelay works with very large delay`() {
        val largeDelay = Duration.ofDays(1)
        val policy = FixedDelayRetryPolicy(largeDelay)

        assertThat(policy.nextDelay(0)).isEqualTo(largeDelay)
        assertThat(policy.nextDelay(1000)).isEqualTo(largeDelay)
    }

    @Test
    fun `policy maintains consistency across multiple calls`() {
        val delay = Duration.ofSeconds(3)
        val policy = FixedDelayRetryPolicy(delay)

        // Call multiple times with same retry count
        assertThat(policy.nextDelay(5)).isEqualTo(delay)
        assertThat(policy.nextDelay(5)).isEqualTo(delay)
        assertThat(policy.nextDelay(5)).isEqualTo(delay)

        // Call with different retry counts
        assertThat(policy.nextDelay(1)).isEqualTo(delay)
        assertThat(policy.nextDelay(10)).isEqualTo(delay)
        assertThat(policy.nextDelay(0)).isEqualTo(delay)
    }
}
