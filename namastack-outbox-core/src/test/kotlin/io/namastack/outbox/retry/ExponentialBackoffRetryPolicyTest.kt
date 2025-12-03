package io.namastack.outbox.retry

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class ExponentialBackoffRetryPolicyTest {
    @Test
    fun `shouldRetry always returns true`() {
        val policy =
            ExponentialBackoffRetryPolicy(
                initialDelay = Duration.ofMillis(100),
                maxDelay = Duration.ofMinutes(5),
                backoffMultiplier = 2.0,
            )

        assertThat(policy.shouldRetry(RuntimeException("Test exception"))).isTrue()
        assertThat(policy.shouldRetry(IllegalStateException("Another exception"))).isTrue()
        assertThat(policy.shouldRetry(Exception("Generic exception"))).isTrue()
    }

    @Test
    fun `nextDelay returns initial delay for first retry`() {
        val initialDelay = Duration.ofMillis(100)
        val policy =
            ExponentialBackoffRetryPolicy(
                initialDelay = initialDelay,
                maxDelay = Duration.ofMinutes(5),
                backoffMultiplier = 2.0,
            )

        val delay = policy.nextDelay(1)
        assertThat(delay).isEqualTo(initialDelay)
    }

    @Test
    fun `nextDelay doubles delay for each retry count`() {
        val initialDelay = Duration.ofMillis(100)
        val policy =
            ExponentialBackoffRetryPolicy(
                initialDelay = initialDelay,
                maxDelay = Duration.ofMinutes(5),
                backoffMultiplier = 2.0,
            )

        assertThat(policy.nextDelay(1)).isEqualTo(Duration.ofMillis(100))
        assertThat(policy.nextDelay(2)).isEqualTo(Duration.ofMillis(200))
        assertThat(policy.nextDelay(3)).isEqualTo(Duration.ofMillis(400))
        assertThat(policy.nextDelay(4)).isEqualTo(Duration.ofMillis(800))
    }

    @Test
    fun `nextDelay caps at maximum delay`() {
        val initialDelay = Duration.ofMillis(100)
        val maxDelay = Duration.ofMillis(500)
        val policy =
            ExponentialBackoffRetryPolicy(
                initialDelay = initialDelay,
                maxDelay = maxDelay,
                backoffMultiplier = 2.0,
            )

        assertThat(policy.nextDelay(1)).isEqualTo(Duration.ofMillis(100))
        assertThat(policy.nextDelay(2)).isEqualTo(Duration.ofMillis(200))
        assertThat(policy.nextDelay(3)).isEqualTo(Duration.ofMillis(400))
        assertThat(policy.nextDelay(4)).isEqualTo(Duration.ofMillis(500))
        assertThat(policy.nextDelay(10)).isEqualTo(Duration.ofMillis(500))
    }

    @Test
    fun `nextDelay handles large retry counts without overflow`() {
        val initialDelay = Duration.ofMillis(1)
        val maxDelay = Duration.ofMinutes(10)
        val policy =
            ExponentialBackoffRetryPolicy(
                initialDelay = initialDelay,
                maxDelay = maxDelay,
                backoffMultiplier = 2.0,
            )

        // Test with very large retry count
        val delay = policy.nextDelay(100)
        assertThat(delay).isEqualTo(maxDelay)
    }

    @Test
    fun `nextDelay works with different time units`() {
        val initialDelay = Duration.ofSeconds(1)
        val maxDelay = Duration.ofMinutes(5)
        val policy =
            ExponentialBackoffRetryPolicy(
                initialDelay = initialDelay,
                maxDelay = maxDelay,
                backoffMultiplier = 2.0,
            )

        assertThat(policy.nextDelay(1)).isEqualTo(Duration.ofSeconds(1))
        assertThat(policy.nextDelay(2)).isEqualTo(Duration.ofSeconds(2))
        assertThat(policy.nextDelay(3)).isEqualTo(Duration.ofSeconds(4))
    }

    @Test
    fun `nextDelay with zero initial delay`() {
        val policy =
            ExponentialBackoffRetryPolicy(
                initialDelay = Duration.ZERO,
                maxDelay = Duration.ofMinutes(5),
                backoffMultiplier = 2.0,
            )

        assertThat(policy.nextDelay(0)).isEqualTo(Duration.ZERO)
        assertThat(policy.nextDelay(1)).isEqualTo(Duration.ZERO)
        assertThat(policy.nextDelay(10)).isEqualTo(Duration.ZERO)
    }

    @Test
    fun `maxDelay equals initialDelay`() {
        val delay = Duration.ofMillis(200)
        val policy =
            ExponentialBackoffRetryPolicy(
                initialDelay = delay,
                maxDelay = delay,
                backoffMultiplier = 2.0,
            )

        assertThat(policy.nextDelay(1)).isEqualTo(delay)
        assertThat(policy.nextDelay(2)).isEqualTo(delay)
        assertThat(policy.nextDelay(5)).isEqualTo(delay)
    }
}
