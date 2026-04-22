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
                        fixedDelay = Duration.ofSeconds(3),
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
                        fixedDelay = Duration.ofSeconds(5),
                        jitter = Duration.ofSeconds(2),
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
                        linearInitial = Duration.ofSeconds(1),
                        linearIncrement = Duration.ofSeconds(2),
                        linearMax = Duration.ofSeconds(7),
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
                        linearInitial = Duration.ofSeconds(1),
                        linearIncrement = Duration.ofSeconds(2),
                        linearMax = Duration.ofSeconds(7),
                        jitter = Duration.ofMillis(500),
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
                        exponentialInitial = Duration.ofSeconds(1),
                        exponentialMultiplier = 2.0,
                        exponentialMax = Duration.ofSeconds(9),
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
                        exponentialInitial = Duration.ofSeconds(1),
                        exponentialMultiplier = 2.0,
                        exponentialMax = Duration.ofSeconds(9),
                        jitter = Duration.ofMillis(500),
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
        fixedDelay: Duration? = null,
        linearInitial: Duration? = null,
        linearIncrement: Duration? = null,
        linearMax: Duration? = null,
        exponentialInitial: Duration? = null,
        exponentialMultiplier: Double? = null,
        exponentialMax: Duration? = null,
        jitter: Duration? = null,
        include: Set<String>? = null,
        exclude: Set<String>? = null,
    ): OutboxProperties.Retry {
        val properties = OutboxProperties.Retry()

        maxRetries?.let { properties.maxRetries = it }
        policy?.let { properties.policy = it }
        fixedDelay?.let { properties.fixed.delay = it }
        linearInitial?.let { properties.linear.initialDelay = it }
        linearIncrement?.let { properties.linear.increment = it }
        linearMax?.let { properties.linear.maxDelay = it }
        exponentialInitial?.let { properties.exponential.initialDelay = it }
        exponentialMultiplier?.let { properties.exponential.multiplier = it }
        exponentialMax?.let { properties.exponential.maxDelay = it }
        jitter?.let { properties.jitter = it }
        include?.let { properties.includeExceptions = it }
        exclude?.let { properties.excludeExceptions = it }

        return properties
    }
}
