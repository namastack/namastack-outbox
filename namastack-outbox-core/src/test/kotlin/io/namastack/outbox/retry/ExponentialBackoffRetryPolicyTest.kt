package io.namastack.outbox.retry

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Duration

@DisplayName("ExponentialBackoffRetryPolicy")
class ExponentialBackoffRetryPolicyTest {
    @Nested
    @DisplayName("nextDelay()")
    inner class NextDelayTests {
        @Test
        fun `should return initial delay for first retry`() {
            val policy =
                ExponentialBackoffRetryPolicy(
                    initialDelay = Duration.ofMillis(100),
                    maxDelay = Duration.ofMinutes(5),
                    backoffMultiplier = 2.0,
                    maxRetries = 3,
                )

            assertThat(policy.nextDelay(1)).isEqualTo(Duration.ofMillis(100))
        }

        @Test
        fun `should double delay for each retry with multiplier 2_0`() {
            val policy =
                ExponentialBackoffRetryPolicy(
                    initialDelay = Duration.ofMillis(100),
                    maxDelay = Duration.ofMinutes(5),
                    backoffMultiplier = 2.0,
                    maxRetries = 5,
                )

            assertThat(policy.nextDelay(1)).isEqualTo(Duration.ofMillis(100))
            assertThat(policy.nextDelay(2)).isEqualTo(Duration.ofMillis(200))
            assertThat(policy.nextDelay(3)).isEqualTo(Duration.ofMillis(400))
            assertThat(policy.nextDelay(4)).isEqualTo(Duration.ofMillis(800))
        }

        @Test
        fun `should cap delay at maximum`() {
            val policy =
                ExponentialBackoffRetryPolicy(
                    initialDelay = Duration.ofMillis(100),
                    maxDelay = Duration.ofMillis(500),
                    backoffMultiplier = 2.0,
                    maxRetries = 10,
                )

            assertThat(policy.nextDelay(4)).isEqualTo(Duration.ofMillis(500))
            assertThat(policy.nextDelay(10)).isEqualTo(Duration.ofMillis(500))
        }

        @Test
        fun `should handle large retry counts without overflow`() {
            val policy =
                ExponentialBackoffRetryPolicy(
                    initialDelay = Duration.ofMillis(1),
                    maxDelay = Duration.ofMinutes(10),
                    backoffMultiplier = 2.0,
                    maxRetries = 100,
                )

            val delay = policy.nextDelay(100)
            assertThat(delay).isLessThanOrEqualTo(Duration.ofMinutes(10))
        }
    }

    @Nested
    @DisplayName("maxRetries()")
    inner class MaxRetriesTests {
        @Test
        fun `should return configured max retries`() {
            val policy =
                ExponentialBackoffRetryPolicy(
                    initialDelay = Duration.ofSeconds(1),
                    maxDelay = Duration.ofMinutes(5),
                    backoffMultiplier = 2.0,
                    maxRetries = 7,
                )

            assertThat(policy.maxRetries()).isEqualTo(7)
        }
    }

    @Nested
    @DisplayName("shouldRetry() - No Exception Filtering")
    inner class ShouldRetryDefaultTests {
        @Test
        fun `should retry all exceptions when no filtering configured`() {
            val policy =
                ExponentialBackoffRetryPolicy(
                    initialDelay = Duration.ofSeconds(1),
                    maxDelay = Duration.ofMinutes(5),
                    backoffMultiplier = 2.0,
                    maxRetries = 3,
                )

            assertThat(policy.shouldRetry(RuntimeException())).isTrue()
            assertThat(policy.shouldRetry(IOException())).isTrue()
            assertThat(policy.shouldRetry(IllegalArgumentException())).isTrue()
        }
    }

    @Nested
    @DisplayName("shouldRetry() - includeExceptions")
    inner class ShouldRetryIncludeTests {
        @Test
        fun `should only retry included exception types`() {
            val policy =
                ExponentialBackoffRetryPolicy(
                    initialDelay = Duration.ofSeconds(1),
                    maxDelay = Duration.ofMinutes(5),
                    backoffMultiplier = 2.0,
                    maxRetries = 3,
                    includeExceptions = setOf(IOException::class, SocketTimeoutException::class),
                )

            assertThat(policy.shouldRetry(IOException())).isTrue()
            assertThat(policy.shouldRetry(SocketTimeoutException())).isTrue()
            assertThat(policy.shouldRetry(IllegalArgumentException())).isFalse()
            assertThat(policy.shouldRetry(RuntimeException())).isFalse()
        }

        @Test
        fun `should match subclasses when using includeExceptions`() {
            val policy =
                ExponentialBackoffRetryPolicy(
                    initialDelay = Duration.ofSeconds(1),
                    maxDelay = Duration.ofMinutes(5),
                    backoffMultiplier = 2.0,
                    maxRetries = 3,
                    includeExceptions = setOf(IOException::class),
                )

            assertThat(policy.shouldRetry(SocketTimeoutException())).isTrue()
        }
    }

    @Nested
    @DisplayName("shouldRetry() - excludeExceptions")
    inner class ShouldRetryExcludeTests {
        @Test
        fun `should not retry excluded exception types`() {
            val policy =
                ExponentialBackoffRetryPolicy(
                    initialDelay = Duration.ofSeconds(1),
                    maxDelay = Duration.ofMinutes(5),
                    backoffMultiplier = 2.0,
                    maxRetries = 3,
                    excludeExceptions = setOf(IllegalArgumentException::class, IllegalStateException::class),
                )

            assertThat(policy.shouldRetry(IllegalArgumentException())).isFalse()
            assertThat(policy.shouldRetry(IllegalStateException())).isFalse()
            assertThat(policy.shouldRetry(IOException())).isTrue()
            assertThat(policy.shouldRetry(RuntimeException())).isTrue()
        }

        @Test
        fun `should match subclasses when using excludeExceptions`() {
            val policy =
                ExponentialBackoffRetryPolicy(
                    initialDelay = Duration.ofSeconds(1),
                    maxDelay = Duration.ofMinutes(5),
                    backoffMultiplier = 2.0,
                    maxRetries = 3,
                    excludeExceptions = setOf(IllegalArgumentException::class),
                )

            assertThat(policy.shouldRetry(IllegalArgumentException())).isFalse()
            assertThat(policy.shouldRetry(IOException())).isTrue()
        }
    }

    @Nested
    @DisplayName("Builder")
    inner class BuilderTests {
        @Test
        fun `should build policy with default values`() {
            val policy = ExponentialBackoffRetryPolicy.builder().build()

            assertThat(policy).isNotNull()
            assertThat(policy.maxRetries()).isEqualTo(3)
            assertThat(policy.nextDelay(1)).isEqualTo(Duration.ofSeconds(1))
        }

        @Test
        fun `should build policy with custom values`() {
            val policy =
                ExponentialBackoffRetryPolicy
                    .builder()
                    .initialDelay(Duration.ofMillis(500))
                    .maxDelay(Duration.ofMinutes(2))
                    .backoffMultiplier(3.0)
                    .maxRetries(5)
                    .build()

            assertThat(policy.maxRetries()).isEqualTo(5)
            assertThat(policy.nextDelay(1)).isEqualTo(Duration.ofMillis(500))
        }

        @Test
        fun `should build policy with includeExceptions`() {
            val policy =
                ExponentialBackoffRetryPolicy
                    .builder()
                    .includeException(IOException::class)
                    .includeException(SocketTimeoutException::class)
                    .build()

            assertThat(policy.shouldRetry(IOException())).isTrue()
            assertThat(policy.shouldRetry(IllegalArgumentException())).isFalse()
        }

        @Test
        fun `should build policy with excludeExceptions`() {
            val policy =
                ExponentialBackoffRetryPolicy
                    .builder()
                    .excludeException(IllegalArgumentException::class)
                    .build()

            assertThat(policy.shouldRetry(IOException())).isTrue()
            assertThat(policy.shouldRetry(IllegalArgumentException())).isFalse()
        }

        @Test
        fun `should throw when both include and exclude are specified`() {
            assertThatThrownBy {
                ExponentialBackoffRetryPolicy
                    .builder()
                    .includeException(IOException::class)
                    .excludeException(IllegalArgumentException::class)
                    .build()
            }.isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("Cannot specify both includeExceptions and excludeExceptions")
        }

        @Test
        fun `should wrap in JitteredRetryPolicy when jitter is configured`() {
            val policy =
                ExponentialBackoffRetryPolicy
                    .builder()
                    .initialDelay(Duration.ofSeconds(1))
                    .jitter(Duration.ofMillis(500))
                    .build()

            assertThat(policy).isInstanceOf(JitteredRetryPolicy::class.java)
        }

        @Test
        fun `should not wrap when jitter is not configured`() {
            val policy =
                ExponentialBackoffRetryPolicy
                    .builder()
                    .initialDelay(Duration.ofSeconds(1))
                    .build()

            assertThat(policy).isInstanceOf(ExponentialBackoffRetryPolicy::class.java)
        }

        @Test
        fun `should support vararg includeExceptions`() {
            val policy =
                ExponentialBackoffRetryPolicy
                    .builder()
                    .includeExceptions(IOException::class, SocketTimeoutException::class)
                    .build()

            assertThat(policy.shouldRetry(IOException())).isTrue()
            assertThat(policy.shouldRetry(SocketTimeoutException())).isTrue()
        }

        @Test
        fun `should support vararg excludeExceptions`() {
            val policy =
                ExponentialBackoffRetryPolicy
                    .builder()
                    .excludeExceptions(IllegalArgumentException::class, IllegalStateException::class)
                    .build()

            assertThat(policy.shouldRetry(IllegalArgumentException())).isFalse()
            assertThat(policy.shouldRetry(IllegalStateException())).isFalse()
        }
    }
}
