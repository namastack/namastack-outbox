package io.namastack.outbox.retry

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Duration

@DisplayName("FixedDelayRetryPolicy")
class FixedDelayRetryPolicyTest {
    @Nested
    @DisplayName("nextDelay()")
    inner class NextDelayTests {
        @Test
        fun `should return same delay regardless of retry count`() {
            val policy =
                FixedDelayRetryPolicy(
                    delay = Duration.ofSeconds(5),
                    maxRetries = 3,
                )

            assertThat(policy.nextDelay(1)).isEqualTo(Duration.ofSeconds(5))
            assertThat(policy.nextDelay(2)).isEqualTo(Duration.ofSeconds(5))
            assertThat(policy.nextDelay(10)).isEqualTo(Duration.ofSeconds(5))
            assertThat(policy.nextDelay(100)).isEqualTo(Duration.ofSeconds(5))
        }

        @Test
        fun `should work with milliseconds`() {
            val policy =
                FixedDelayRetryPolicy(
                    delay = Duration.ofMillis(500),
                    maxRetries = 3,
                )

            assertThat(policy.nextDelay(1)).isEqualTo(Duration.ofMillis(500))
        }

        @Test
        fun `should work with zero delay`() {
            val policy =
                FixedDelayRetryPolicy(
                    delay = Duration.ZERO,
                    maxRetries = 3,
                )

            assertThat(policy.nextDelay(1)).isEqualTo(Duration.ZERO)
        }
    }

    @Nested
    @DisplayName("maxRetries()")
    inner class MaxRetriesTests {
        @Test
        fun `should return configured max retries`() {
            val policy =
                FixedDelayRetryPolicy(
                    delay = Duration.ofSeconds(5),
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
                FixedDelayRetryPolicy(
                    delay = Duration.ofSeconds(5),
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
                FixedDelayRetryPolicy(
                    delay = Duration.ofSeconds(5),
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
                FixedDelayRetryPolicy(
                    delay = Duration.ofSeconds(5),
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
                FixedDelayRetryPolicy(
                    delay = Duration.ofSeconds(5),
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
                FixedDelayRetryPolicy(
                    delay = Duration.ofSeconds(5),
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
            val policy = FixedDelayRetryPolicy.builder().build()

            assertThat(policy).isNotNull()
            assertThat(policy.maxRetries()).isEqualTo(3)
            assertThat(policy.nextDelay(1)).isEqualTo(Duration.ofSeconds(5))
        }

        @Test
        fun `should build policy with custom values`() {
            val policy =
                FixedDelayRetryPolicy
                    .builder()
                    .delay(Duration.ofSeconds(10))
                    .maxRetries(5)
                    .build()

            assertThat(policy.maxRetries()).isEqualTo(5)
            assertThat(policy.nextDelay(1)).isEqualTo(Duration.ofSeconds(10))
        }

        @Test
        fun `should build policy with includeExceptions`() {
            val policy =
                FixedDelayRetryPolicy
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
                FixedDelayRetryPolicy
                    .builder()
                    .excludeException(IllegalArgumentException::class)
                    .build()

            assertThat(policy.shouldRetry(IOException())).isTrue()
            assertThat(policy.shouldRetry(IllegalArgumentException())).isFalse()
        }

        @Test
        fun `should throw when both include and exclude are specified`() {
            assertThatThrownBy {
                FixedDelayRetryPolicy
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
                FixedDelayRetryPolicy
                    .builder()
                    .delay(Duration.ofSeconds(5))
                    .jitter(Duration.ofMillis(500))
                    .build()

            assertThat(policy).isInstanceOf(JitteredRetryPolicy::class.java)
        }

        @Test
        fun `should not wrap when jitter is not configured`() {
            val policy =
                FixedDelayRetryPolicy
                    .builder()
                    .delay(Duration.ofSeconds(5))
                    .build()

            assertThat(policy).isInstanceOf(FixedDelayRetryPolicy::class.java)
        }

        @Test
        fun `should support vararg includeExceptions`() {
            val policy =
                FixedDelayRetryPolicy
                    .builder()
                    .includeExceptions(IOException::class, SocketTimeoutException::class)
                    .build()

            assertThat(policy.shouldRetry(IOException())).isTrue()
            assertThat(policy.shouldRetry(SocketTimeoutException())).isTrue()
        }

        @Test
        fun `should support vararg excludeExceptions`() {
            val policy =
                FixedDelayRetryPolicy
                    .builder()
                    .excludeExceptions(IllegalArgumentException::class, IllegalStateException::class)
                    .build()

            assertThat(policy.shouldRetry(IllegalArgumentException())).isFalse()
            assertThat(policy.shouldRetry(IllegalStateException())).isFalse()
        }
    }
}
