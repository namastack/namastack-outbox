package io.namastack.outbox.retry

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration

class OutboxRetryPolicyTest {
    @Nested
    @DisplayName("shouldRetry()")
    inner class ShouldRetryTests {
        @Test
        fun `defaults to retry when no rules are defined`() {
            val policy =
                OutboxRetryPolicy
                    .builder()
                    .build()

            assertThat(policy.shouldRetry(RetryableException())).isTrue()
            assertThat(policy.shouldRetry(NonRetryableException())).isTrue()
            assertThat(policy.shouldRetry(UndefinedException())).isTrue()
        }

        @Test
        fun `retries only for configured retryable exceptions`() {
            val policy =
                OutboxRetryPolicy
                    .builder()
                    .retryOn(RetryableException::class.java)
                    .build()

            assertThat(policy.shouldRetry(RetryableException())).isTrue()
            assertThat(policy.shouldRetry(UndefinedException())).isFalse()
        }

        @Test
        fun `does not retry when exception is explicitly non-retryable`() {
            val policy =
                OutboxRetryPolicy
                    .builder()
                    .noRetryOn(NonRetryableException::class.java)
                    .build()

            assertThat(policy.shouldRetry(NonRetryableException())).isFalse()
            assertThat(policy.shouldRetry(UndefinedException())).isTrue()
        }

        @Test
        fun `predicate enables retry when matching`() {
            val policy =
                OutboxRetryPolicy
                    .builder()
                    .retryIf { it is RetryableException }
                    .build()

            assertThat(policy.shouldRetry(RetryableException())).isTrue()
            assertThat(policy.shouldRetry(UndefinedException())).isFalse()
        }

        @Test
        fun `retryable and non-retryable`() {
            val policy =
                OutboxRetryPolicy
                    .builder()
                    .retryOn(RetryableException::class.java)
                    .noRetryOn(NonRetryableException::class.java)
                    .build()

            assertThat(policy.shouldRetry(RetryableException())).isTrue()
            assertThat(policy.shouldRetry(NonRetryableException())).isFalse()
            assertThat(policy.shouldRetry(UndefinedException())).isFalse()
        }

        @Test
        fun `retryable and predicate`() {
            val policy =
                OutboxRetryPolicy
                    .builder()
                    .retryOn(RetryableException::class.java)
                    .retryIf { it is OtherRetryableException }
                    .build()

            assertThat(policy.shouldRetry(RetryableException())).isTrue()
            assertThat(policy.shouldRetry(OtherRetryableException())).isTrue()
            assertThat(policy.shouldRetry(UndefinedException())).isFalse()
        }

        @Test
        fun `non-retryable and predicate`() {
            val policy =
                OutboxRetryPolicy
                    .builder()
                    .noRetryOn(NonRetryableException::class.java)
                    .retryIf { it is RetryableException }
                    .build()

            assertThat(policy.shouldRetry(NonRetryableException())).isFalse()
            assertThat(policy.shouldRetry(RetryableException())).isTrue()
            assertThat(policy.shouldRetry(UndefinedException())).isFalse()
        }

        @Test
        fun `retryable, non-retryable and predicate`() {
            val policy =
                OutboxRetryPolicy
                    .builder()
                    .retryOn(RetryableException::class.java)
                    .noRetryOn(NonRetryableException::class.java)
                    .retryIf { it is OtherRetryableException }
                    .build()

            assertThat(policy.shouldRetry(RetryableException())).isTrue()
            assertThat(policy.shouldRetry(NonRetryableException())).isFalse()
            assertThat(policy.shouldRetry(OtherRetryableException())).isTrue()
            assertThat(policy.shouldRetry(UndefinedException())).isFalse()
        }

        @Test
        fun `supports multiple retryable and non-retryable exceptions`() {
            val policy =
                OutboxRetryPolicy
                    .builder()
                    .retryOn(listOf(RetryableException::class.java, OtherRetryableException::class.java))
                    .noRetryOn(listOf(NonRetryableException::class.java, OtherNonRetryableException::class.java))
                    .build()

            assertThat(policy.shouldRetry(RetryableException())).isTrue()
            assertThat(policy.shouldRetry(OtherRetryableException())).isTrue()
            assertThat(policy.shouldRetry(NonRetryableException())).isFalse()
            assertThat(policy.shouldRetry(OtherNonRetryableException())).isFalse()
            assertThat(policy.shouldRetry(UndefinedException())).isFalse()
        }

        @Test
        fun `retryOn accumulates exceptions instead of replacing them`() {
            val policy =
                OutboxRetryPolicy
                    .builder()
                    .retryOn(RetryableException::class.java)
                    .retryOn(OtherRetryableException::class.java)
                    .build()

            assertThat(policy.shouldRetry(RetryableException())).isTrue()
            assertThat(policy.shouldRetry(OtherRetryableException())).isTrue()
            assertThat(policy.shouldRetry(UndefinedException())).isFalse()
        }

        @Test
        fun `noRetryOn accumulates exceptions instead of replacing them`() {
            val policy =
                OutboxRetryPolicy
                    .builder()
                    .noRetryOn(NonRetryableException::class.java)
                    .noRetryOn(OtherNonRetryableException::class.java)
                    .build()

            assertThat(policy.shouldRetry(NonRetryableException())).isFalse()
            assertThat(policy.shouldRetry(OtherNonRetryableException())).isFalse()
            assertThat(policy.shouldRetry(UndefinedException())).isTrue()
        }

        @Test
        fun `retryIf accumulates predicates using OR logic`() {
            val policy =
                OutboxRetryPolicy
                    .builder()
                    .retryIf { it is RetryableException }
                    .retryIf { it is OtherRetryableException }
                    .build()

            assertThat(policy.shouldRetry(RetryableException())).isTrue()
            assertThat(policy.shouldRetry(OtherRetryableException())).isTrue()
            assertThat(policy.shouldRetry(UndefinedException())).isFalse()
        }
    }

    @Nested
    @DisplayName("nextDelay()")
    inner class NextDelayTests {
        @Nested
        @DisplayName("backoff strategies")
        inner class BackoffTests {
            @Test
            fun `fixed backoff uses constant delay`() {
                val policy =
                    OutboxRetryPolicy
                        .builder()
                        .fixedBackOff(Duration.ofSeconds(3))
                        .build()

                assertThat(policy.nextDelay(1)).isEqualTo(Duration.ofSeconds(3))
                assertThat(policy.nextDelay(2)).isEqualTo(Duration.ofSeconds(3))
                assertThat(policy.nextDelay(10)).isEqualTo(Duration.ofSeconds(3))
            }

            @Test
            fun `linear backoff increases by increment and caps at max`() {
                val policy =
                    OutboxRetryPolicy
                        .builder()
                        .linearBackoff(
                            initialDelay = Duration.ofSeconds(1),
                            increment = Duration.ofSeconds(2),
                            maxDelay = Duration.ofSeconds(7),
                        ).build()

                // failureCount: 1 -> 1s, 2 -> 3s, 3 -> 5s, 4 -> 7s (cap), 5 -> 7s
                assertThat(policy.nextDelay(1)).isEqualTo(Duration.ofSeconds(1))
                assertThat(policy.nextDelay(2)).isEqualTo(Duration.ofSeconds(3))
                assertThat(policy.nextDelay(3)).isEqualTo(Duration.ofSeconds(5))
                assertThat(policy.nextDelay(4)).isEqualTo(Duration.ofSeconds(7))
                assertThat(policy.nextDelay(5)).isEqualTo(Duration.ofSeconds(7))
            }

            @Test
            fun `exponential backoff multiplies and caps at max`() {
                val policy =
                    OutboxRetryPolicy
                        .builder()
                        .exponentialBackoff(
                            initialDelay = Duration.ofSeconds(1),
                            multiplier = 2.0,
                            maxDelay = Duration.ofSeconds(9),
                        ).build()

                // 1 -> 1s, 2 -> 2s, 3 -> 4s, 4 -> 8s, 5 -> 9s (cap)
                assertThat(policy.nextDelay(1)).isEqualTo(Duration.ofSeconds(1))
                assertThat(policy.nextDelay(2)).isEqualTo(Duration.ofSeconds(2))
                assertThat(policy.nextDelay(3)).isEqualTo(Duration.ofSeconds(4))
                assertThat(policy.nextDelay(4)).isEqualTo(Duration.ofSeconds(8))
                assertThat(policy.nextDelay(5)).isEqualTo(Duration.ofSeconds(9))
            }
        }

        @Nested
        @DisplayName("jitter")
        inner class JitterTests {
            @Test
            fun `applies jitter within bounds when set`() {
                val base = Duration.ofSeconds(5)
                val jitter = Duration.ofSeconds(2)

                val policy =
                    OutboxRetryPolicy
                        .builder()
                        .fixedBackOff(base)
                        .jitter(jitter)
                        .build()

                repeat(100) {
                    // sample many times due to randomness
                    val d = policy.nextDelay(1)
                    val min = base.minus(jitter)
                    val max = base.plus(jitter)
                    assertThat(d).isBetween(min, max)
                }
            }

            @Test
            fun `no jitter applied when zero`() {
                val base = Duration.ofSeconds(5)

                val zeroJitterPolicy =
                    OutboxRetryPolicy
                        .builder()
                        .fixedBackOff(base)
                        .jitter(Duration.ZERO)
                        .build()

                val defaultJitterPolicy =
                    OutboxRetryPolicy
                        .builder()
                        .fixedBackOff(base)
                        .build() // default jitter is ZERO

                assertThat(zeroJitterPolicy.nextDelay(3)).isEqualTo(base)
                assertThat(defaultJitterPolicy.nextDelay(3)).isEqualTo(base)
            }
        }
    }

    @Nested
    @DisplayName("maxRetries()")
    inner class MaxRetriesTests {
        @Test
        fun `default maxRetries is positive`() {
            val policy =
                OutboxRetryPolicy
                    .builder()
                    .build()

            assertThat(policy.maxRetries()).isPositive()
        }

        @Test
        fun `can set maxRetries`() {
            val policy =
                OutboxRetryPolicy
                    .builder()
                    .maxRetries(10)
                    .build()

            assertThat(policy.maxRetries()).isEqualTo(10)
        }
    }

    @Nested
    @DisplayName("validation")
    inner class ValidationTests {
        @Test
        fun `fixedBackOff requires positive delay`() {
            assertThatThrownBy {
                OutboxRetryPolicy
                    .builder()
                    .fixedBackOff(delay = Duration.ZERO)
            }.isInstanceOf(IllegalArgumentException::class.java)

            assertThatThrownBy {
                OutboxRetryPolicy
                    .builder()
                    .fixedBackOff(delay = Duration.ofSeconds(-1))
            }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `linearBackoff requires positive values`() {
            assertThatThrownBy {
                OutboxRetryPolicy
                    .builder()
                    .linearBackoff(
                        initialDelay = Duration.ZERO,
                        increment = Duration.ofSeconds(1),
                        maxDelay = Duration.ofSeconds(10),
                    )
            }.isInstanceOf(IllegalArgumentException::class.java)

            assertThatThrownBy {
                OutboxRetryPolicy
                    .builder()
                    .linearBackoff(
                        initialDelay = Duration.ofSeconds(1),
                        increment = Duration.ZERO,
                        maxDelay = Duration.ofSeconds(10),
                    )
            }.isInstanceOf(IllegalArgumentException::class.java)

            assertThatThrownBy {
                OutboxRetryPolicy
                    .builder()
                    .linearBackoff(
                        initialDelay = Duration.ofSeconds(1),
                        increment = Duration.ofSeconds(1),
                        maxDelay = Duration.ZERO,
                    )
            }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `exponentialBackoff requires multiplier greater than 1 and positive durations`() {
            assertThatThrownBy {
                OutboxRetryPolicy
                    .builder()
                    .exponentialBackoff(
                        initialDelay = Duration.ZERO,
                        multiplier = 2.0,
                        maxDelay = Duration.ofSeconds(1),
                    )
            }.isInstanceOf(IllegalArgumentException::class.java)

            assertThatThrownBy {
                OutboxRetryPolicy
                    .builder()
                    .exponentialBackoff(
                        initialDelay = Duration.ofSeconds(1),
                        multiplier = 1.0,
                        maxDelay = Duration.ofSeconds(1),
                    )
            }.isInstanceOf(IllegalArgumentException::class.java)

            assertThatThrownBy {
                OutboxRetryPolicy
                    .builder()
                    .exponentialBackoff(
                        initialDelay = Duration.ofSeconds(1),
                        multiplier = 2.0,
                        maxDelay = Duration.ZERO,
                    )
            }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `jitter requires non-negative duration`() {
            assertThatThrownBy {
                OutboxRetryPolicy
                    .builder()
                    .jitter(jitter = Duration.ofSeconds(-1))
            }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `maxRetries requires positive value`() {
            assertThatThrownBy {
                OutboxRetryPolicy
                    .builder()
                    .maxRetries(maxRetries = 0)
            }.isInstanceOf(IllegalArgumentException::class.java)

            assertThatThrownBy {
                OutboxRetryPolicy
                    .builder()
                    .maxRetries(maxRetries = -1)
            }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    class RetryableException : RuntimeException()

    class OtherRetryableException : RuntimeException()

    class NonRetryableException : RuntimeException()

    class OtherNonRetryableException : RuntimeException()

    class UndefinedException : RuntimeException()
}
