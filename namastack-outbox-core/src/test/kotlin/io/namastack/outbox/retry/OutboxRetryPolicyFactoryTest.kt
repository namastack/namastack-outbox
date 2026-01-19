package io.namastack.outbox.retry

import io.namastack.outbox.OutboxProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration

class OutboxRetryPolicyFactoryTest {
    @Nested
    @DisplayName("max retries")
    inner class MaxRetriesTests {
        @Test
        fun `default maxRetries`() {
            val prop = properties()
            val policy = OutboxRetryPolicyFactory.createDefault(prop).build()

            assertThat(policy.maxRetries()).isEqualTo(3)
        }

        @Test
        fun `can set maxRetries`() {
            val prop =
                properties(
                    maxRetries = 10,
                )
            val policy = OutboxRetryPolicyFactory.createDefault(prop).build()

            assertThat(policy.maxRetries()).isEqualTo(10)
        }
    }

    @Nested
    @DisplayName("supported policies")
    inner class SupportedPolicies {
        @Nested
        @DisplayName("fixed policy")
        inner class FixedPolicyTests {
            @Test
            fun `fixed backoff`() {
                val prop =
                    properties(
                        policy = "fixed",
                        fixedDelayMs = 3000,
                    )
                val policy = OutboxRetryPolicyFactory.createDefault(prop).build()

                assertThat(policy.nextDelay(1)).isEqualTo(Duration.ofSeconds(3))
                assertThat(policy.nextDelay(2)).isEqualTo(Duration.ofSeconds(3))
                assertThat(policy.nextDelay(10)).isEqualTo(Duration.ofSeconds(3))
            }

            @Test
            fun `fixed backoff and jitter`() {
                val prop =
                    properties(
                        policy = "fixed",
                        fixedDelayMs = 5000,
                        jitterMs = 2000,
                    )
                val policy = OutboxRetryPolicyFactory.createDefault(prop).build()

                val base = Duration.ofMillis(5000)
                val jitter = Duration.ofMillis(2000)
                val min = base.minus(jitter)
                val max = base.plus(jitter)

                repeat(100) {
                    // sample many times due to randomness
                    val d = policy.nextDelay(1)
                    assertThat(d).isBetween(min, max)
                }
            }

            @Test
            fun `fixed backoff and jitter (deprecated)`() {
                val prop =
                    properties(
                        policy = "jittered",
                        fixedDelayMs = 5000,
                        jitteredBasePolicy = "fixed",
                        jitteredJitterMs = 500,
                    )
                val policy = OutboxRetryPolicyFactory.createDefault(prop).build()

                val base = Duration.ofMillis(5000)
                val jitter = Duration.ofMillis(2000)
                val min = base.minus(jitter)
                val max = base.plus(jitter)

                repeat(100) {
                    // sample many times due to randomness
                    val d = policy.nextDelay(1)
                    assertThat(d).isBetween(min, max)
                }
            }
        }

        @Nested
        @DisplayName("linear policy")
        inner class LinearPolicyTests {
            @Test
            fun `linear backoff`() {
                val prop =
                    properties(
                        policy = "linear",
                        linearInitialMs = 1000,
                        linearIncrementMs = 2000,
                        linearMaxMs = 7000,
                    )
                val policy = OutboxRetryPolicyFactory.createDefault(prop).build()

                // failureCount: 1 -> 1s, 2 -> 3s, 3 -> 5s, 4 -> 7s (cap), 5 -> 7s
                assertThat(policy.nextDelay(1)).isEqualTo(Duration.ofSeconds(1))
                assertThat(policy.nextDelay(2)).isEqualTo(Duration.ofSeconds(3))
                assertThat(policy.nextDelay(3)).isEqualTo(Duration.ofSeconds(5))
                assertThat(policy.nextDelay(4)).isEqualTo(Duration.ofSeconds(7))
                assertThat(policy.nextDelay(5)).isEqualTo(Duration.ofSeconds(7))
            }

            @Test
            fun `linear backoff and jitter`() {
                val prop =
                    properties(
                        policy = "linear",
                        linearInitialMs = 1000,
                        linearIncrementMs = 2000,
                        linearMaxMs = 7000,
                        jitterMs = 500,
                    )
                val policy = OutboxRetryPolicyFactory.createDefault(prop).build()

                val base = Duration.ofMillis(1000)
                val jitter = Duration.ofMillis(500)
                val min = base.minus(jitter)
                val max = base.plus(jitter)

                repeat(100) {
                    // sample many times due to randomness
                    val d = policy.nextDelay(1)
                    assertThat(d).isBetween(min, max)
                }
            }

            @Test
            fun `linear backoff and jitter (deprecated)`() {
                val prop =
                    properties(
                        policy = "jittered",
                        linearInitialMs = 1000,
                        linearIncrementMs = 2000,
                        linearMaxMs = 7000,
                        jitteredBasePolicy = "linear",
                        jitteredJitterMs = 500,
                    )
                val policy = OutboxRetryPolicyFactory.createDefault(prop).build()

                val base = Duration.ofMillis(1000)
                val jitter = Duration.ofMillis(500)
                val min = base.minus(jitter)
                val max = base.plus(jitter)

                repeat(100) {
                    // sample many times due to randomness
                    val d = policy.nextDelay(1)
                    assertThat(d).isBetween(min, max)
                }
            }
        }

        @Nested
        @DisplayName("exponential policy")
        inner class ExponentialPolicyTests {
            @Test
            fun `exponential backoff`() {
                val prop =
                    properties(
                        policy = "exponential",
                        exponentialInitialMs = 1000,
                        exponentialMultiplier = 2.0,
                        exponentialMaxMs = 9000,
                    )
                val policy = OutboxRetryPolicyFactory.createDefault(prop).build()

                // 1 -> 1s, 2 -> 2s, 3 -> 4s, 4 -> 8s, 5 -> 9s (cap)
                assertThat(policy.nextDelay(1)).isEqualTo(Duration.ofSeconds(1))
                assertThat(policy.nextDelay(2)).isEqualTo(Duration.ofSeconds(2))
                assertThat(policy.nextDelay(3)).isEqualTo(Duration.ofSeconds(4))
                assertThat(policy.nextDelay(4)).isEqualTo(Duration.ofSeconds(8))
                assertThat(policy.nextDelay(5)).isEqualTo(Duration.ofSeconds(9))
            }

            @Test
            fun `exponential backoff and jitter`() {
                val prop =
                    properties(
                        policy = "exponential",
                        exponentialInitialMs = 1000,
                        exponentialMultiplier = 2.0,
                        exponentialMaxMs = 9000,
                        jitterMs = 500,
                    )
                val policy = OutboxRetryPolicyFactory.createDefault(prop).build()

                val base = Duration.ofMillis(1000)
                val jitter = Duration.ofMillis(500)
                val min = base.minus(jitter)
                val max = base.plus(jitter)

                repeat(100) {
                    // sample many times due to randomness
                    val d = policy.nextDelay(1)
                    assertThat(d).isBetween(min, max)
                }
            }

            @Test
            fun `exponential backoff and jitter (deprecated)`() {
                val prop =
                    properties(
                        policy = "jittered",
                        exponentialInitialMs = 1000,
                        exponentialMultiplier = 2.0,
                        exponentialMaxMs = 9000,
                        jitteredBasePolicy = "exponential",
                        jitteredJitterMs = 500,
                    )
                val policy = OutboxRetryPolicyFactory.createDefault(prop).build()

                val base = Duration.ofMillis(1000)
                val jitter = Duration.ofMillis(500)
                val min = base.minus(jitter)
                val max = base.plus(jitter)

                repeat(100) {
                    // sample many times due to randomness
                    val d = policy.nextDelay(1)
                    assertThat(d).isBetween(min, max)
                }
            }
        }

        @Nested
        @DisplayName("error handling")
        inner class ErrorHandling {
            @Test
            fun `unsupported policy fails`() {
                val prop = properties(policy = "random")
                assertThatThrownBy { OutboxRetryPolicyFactory.createDefault(prop) }
                    .isInstanceOf(IllegalStateException::class.java)
                    .hasMessageContaining("Unsupported retry-policy")
            }

            @Test
            fun `unsupported jittered base policy fails`() {
                val prop = properties(policy = "jittered", jitteredBasePolicy = "random")
                assertThatThrownBy { OutboxRetryPolicyFactory.createDefault(prop) }
                    .isInstanceOf(IllegalStateException::class.java)
                    .hasMessageContaining("Unsupported jittered base policy")
            }
        }
    }

    @Nested
    @DisplayName("exception include/exclude conversion")
    inner class ExceptionConversion {
        @Test
        fun `includes and excludes are applied to builder`() {
            val prop =
                properties(
                    include = setOf(IllegalArgumentException::class.java.name, IllegalStateException::class.java.name),
                    exclude = setOf(UnsupportedOperationException::class.java.name),
                )
            val policy = OutboxRetryPolicyFactory.createDefault(prop).build()

            assertThat(policy.shouldRetry(IllegalArgumentException())).isTrue()
            assertThat(policy.shouldRetry(IllegalStateException())).isTrue()
            assertThat(policy.shouldRetry(UnsupportedOperationException())).isFalse()
        }

        @Test
        fun `fails if class not found`() {
            val prop =
                properties(
                    include = setOf("com.example.DoesNotExist"),
                )

            assertThatThrownBy { OutboxRetryPolicyFactory.createDefault(prop) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Exception class not found")
        }

        @Test
        fun `fails if class is not a Throwable`() {
            val prop =
                properties(
                    include = setOf(String::class.java.name),
                )

            assertThatThrownBy { OutboxRetryPolicyFactory.createDefault(prop) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("is not a Throwable")
        }
    }

    private fun properties(
        maxRetries: Int? = null,
        policy: String? = null,
        fixedDelayMs: Long? = null,
        linearInitialMs: Long? = null,
        linearIncrementMs: Long? = null,
        linearMaxMs: Long? = null,
        exponentialInitialMs: Long? = null,
        exponentialMultiplier: Double? = null,
        exponentialMaxMs: Long? = null,
        jitterMs: Long? = null,
        jitteredBasePolicy: String? = null,
        jitteredJitterMs: Long? = null,
        include: Set<String>? = null,
        exclude: Set<String>? = null,
    ): OutboxProperties.Retry {
        val properties = OutboxProperties.Retry()

        maxRetries?.let { properties.maxRetries = it }
        policy?.let { properties.policy = it }
        fixedDelayMs?.let { properties.fixed.delay = it }
        linearInitialMs?.let { properties.linear.initialDelay = it }
        linearIncrementMs?.let { properties.linear.increment = it }
        linearMaxMs?.let { properties.linear.maxDelay = it }
        exponentialInitialMs?.let { properties.exponential.initialDelay = it }
        exponentialMultiplier?.let { properties.exponential.multiplier = it }
        exponentialMaxMs?.let { properties.exponential.maxDelay = it }
        jitterMs?.let { properties.jitter = it }
        jitteredBasePolicy?.let { properties.jittered.basePolicy = it }
        jitteredJitterMs?.let { properties.jittered.jitter = it }
        include?.let { properties.includeExceptions = it }
        exclude?.let { properties.excludeExceptions = it }

        return properties
    }
}
