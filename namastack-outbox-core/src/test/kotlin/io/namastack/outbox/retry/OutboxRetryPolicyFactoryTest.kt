package io.namastack.outbox.retry

import io.namastack.outbox.OutboxProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration

@DisplayName("OutboxRetryPolicyFactory")
class OutboxRetryPolicyFactoryTest {
    @Nested
    @DisplayName("createDefault() - Fixed Policy")
    inner class CreateFixedPolicyTests {
        @Test
        fun `should create FixedDelayRetryPolicy when name is 'fixed'`() {
            val properties =
                createRetryProperties(
                    maxRetries = 5,
                    fixedDelay = 3000L,
                )

            val policy = OutboxRetryPolicyFactory.createDefault("fixed", properties)

            assertThat(policy).isInstanceOf(FixedDelayRetryPolicy::class.java)
            assertThat(policy.maxRetries()).isEqualTo(5)
            assertThat(policy.nextDelay(1)).isEqualTo(Duration.ofMillis(3000))
        }

        @Test
        fun `should create FixedDelayRetryPolicy with case-insensitive name`() {
            val properties = createRetryProperties(fixedDelay = 1000L)

            val policyLower = OutboxRetryPolicyFactory.createDefault("fixed", properties)
            val policyUpper = OutboxRetryPolicyFactory.createDefault("FIXED", properties)
            val policyMixed = OutboxRetryPolicyFactory.createDefault("FiXeD", properties)

            assertThat(policyLower).isInstanceOf(FixedDelayRetryPolicy::class.java)
            assertThat(policyUpper).isInstanceOf(FixedDelayRetryPolicy::class.java)
            assertThat(policyMixed).isInstanceOf(FixedDelayRetryPolicy::class.java)
        }

        @Test
        fun `should create FixedDelayRetryPolicy with configured delay`() {
            val properties = createRetryProperties(fixedDelay = 5000L)

            val policy = OutboxRetryPolicyFactory.createDefault("fixed", properties)

            assertThat(policy.nextDelay(1)).isEqualTo(Duration.ofMillis(5000))
            assertThat(policy.nextDelay(10)).isEqualTo(Duration.ofMillis(5000))
        }
    }

    @Nested
    @DisplayName("createDefault() - Exponential Policy")
    inner class CreateExponentialPolicyTests {
        @Test
        fun `should create ExponentialBackoffRetryPolicy when name is 'exponential'`() {
            val properties =
                createRetryProperties(
                    maxRetries = 7,
                    exponentialInitialDelay = 1000L,
                    exponentialMaxDelay = 60000L,
                    exponentialMultiplier = 2.0,
                )

            val policy = OutboxRetryPolicyFactory.createDefault("exponential", properties)

            assertThat(policy).isInstanceOf(ExponentialBackoffRetryPolicy::class.java)
            assertThat(policy.maxRetries()).isEqualTo(7)
            assertThat(policy.nextDelay(1)).isEqualTo(Duration.ofMillis(1000))
        }

        @Test
        fun `should create ExponentialBackoffRetryPolicy with case-insensitive name`() {
            val properties = createRetryProperties()

            val policyLower = OutboxRetryPolicyFactory.createDefault("exponential", properties)
            val policyUpper = OutboxRetryPolicyFactory.createDefault("EXPONENTIAL", properties)
            val policyMixed = OutboxRetryPolicyFactory.createDefault("ExPoNeNtIaL", properties)

            assertThat(policyLower).isInstanceOf(ExponentialBackoffRetryPolicy::class.java)
            assertThat(policyUpper).isInstanceOf(ExponentialBackoffRetryPolicy::class.java)
            assertThat(policyMixed).isInstanceOf(ExponentialBackoffRetryPolicy::class.java)
        }

        @Test
        fun `should create ExponentialBackoffRetryPolicy with exponential growth`() {
            val properties =
                createRetryProperties(
                    exponentialInitialDelay = 100L,
                    exponentialMaxDelay = 10000L,
                    exponentialMultiplier = 3.0,
                )

            val policy = OutboxRetryPolicyFactory.createDefault("exponential", properties)

            assertThat(policy.nextDelay(1)).isEqualTo(Duration.ofMillis(100))
            assertThat(policy.nextDelay(2)).isEqualTo(Duration.ofMillis(300))
            assertThat(policy.nextDelay(3)).isEqualTo(Duration.ofMillis(900))
        }

        @Test
        fun `should create ExponentialBackoffRetryPolicy with max delay cap`() {
            val properties =
                createRetryProperties(
                    exponentialInitialDelay = 100L,
                    exponentialMaxDelay = 500L,
                    exponentialMultiplier = 2.0,
                )

            val policy = OutboxRetryPolicyFactory.createDefault("exponential", properties)

            assertThat(policy.nextDelay(10)).isEqualTo(Duration.ofMillis(500))
        }
    }

    @Nested
    @DisplayName("createDefault() - Jittered Policy")
    inner class CreateJitteredPolicyTests {
        @Test
        fun `should create JitteredRetryPolicy when name is 'jittered'`() {
            val properties =
                createRetryProperties(
                    jitteredBasePolicy = "fixed",
                    jitteredJitter = 500L,
                    fixedDelay = 2000L,
                )

            val policy = OutboxRetryPolicyFactory.createDefault("jittered", properties)

            assertThat(policy).isInstanceOf(JitteredRetryPolicy::class.java)
        }

        @Test
        fun `should create JitteredRetryPolicy with case-insensitive name`() {
            val properties =
                createRetryProperties(
                    jitteredBasePolicy = "fixed",
                    jitteredJitter = 500L,
                )

            val policyLower = OutboxRetryPolicyFactory.createDefault("jittered", properties)
            val policyUpper = OutboxRetryPolicyFactory.createDefault("JITTERED", properties)
            val policyMixed = OutboxRetryPolicyFactory.createDefault("JiTtErEd", properties)

            assertThat(policyLower).isInstanceOf(JitteredRetryPolicy::class.java)
            assertThat(policyUpper).isInstanceOf(JitteredRetryPolicy::class.java)
            assertThat(policyMixed).isInstanceOf(JitteredRetryPolicy::class.java)
        }

        @Test
        fun `should create JitteredRetryPolicy wrapping fixed policy`() {
            val properties =
                createRetryProperties(
                    jitteredBasePolicy = "fixed",
                    jitteredJitter = 500L,
                    fixedDelay = 3000L,
                )

            val policy = OutboxRetryPolicyFactory.createDefault("jittered", properties)

            assertThat(policy).isInstanceOf(JitteredRetryPolicy::class.java)
            val delay = policy.nextDelay(1)
            assertThat(delay).isBetween(Duration.ofMillis(3000), Duration.ofMillis(3500))
        }

        @Test
        fun `should create JitteredRetryPolicy wrapping exponential policy`() {
            val properties =
                createRetryProperties(
                    jitteredBasePolicy = "exponential",
                    jitteredJitter = 200L,
                    exponentialInitialDelay = 1000L,
                    exponentialMaxDelay = 60000L,
                    exponentialMultiplier = 2.0,
                )

            val policy = OutboxRetryPolicyFactory.createDefault("jittered", properties)

            assertThat(policy).isInstanceOf(JitteredRetryPolicy::class.java)
            val delay = policy.nextDelay(1)
            assertThat(delay).isBetween(Duration.ofMillis(1000), Duration.ofMillis(1200))
        }

        @Test
        fun `should throw when base policy is jittered`() {
            val properties =
                createRetryProperties(
                    jitteredBasePolicy = "jittered",
                    jitteredJitter = 500L,
                )

            assertThatThrownBy {
                OutboxRetryPolicyFactory.createDefault("jittered", properties)
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Cannot create a jittered policy with jittered base policy")
        }

        @Test
        fun `should throw when base policy is jittered with different case`() {
            val properties =
                createRetryProperties(
                    jitteredBasePolicy = "JITTERED",
                    jitteredJitter = 500L,
                )

            assertThatThrownBy {
                OutboxRetryPolicyFactory.createDefault("jittered", properties)
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Cannot create a jittered policy with jittered base policy")
        }
    }

    @Nested
    @DisplayName("createDefault() - Error Cases")
    inner class ErrorCasesTests {
        @Test
        fun `should throw when policy name is unsupported`() {
            val properties = createRetryProperties()

            assertThatThrownBy {
                OutboxRetryPolicyFactory.createDefault("unknown", properties)
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Unsupported retry-policy: unknown")
        }

        @Test
        fun `should throw for empty policy name`() {
            val properties = createRetryProperties()

            assertThatThrownBy {
                OutboxRetryPolicyFactory.createDefault("", properties)
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Unsupported retry-policy:")
        }

        @Test
        fun `should throw for null-like policy name`() {
            val properties = createRetryProperties()

            assertThatThrownBy {
                OutboxRetryPolicyFactory.createDefault("null", properties)
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Unsupported retry-policy: null")
        }
    }

    @Nested
    @DisplayName("createDefault() - Integration")
    inner class IntegrationTests {
        @Test
        fun `should create policies with different maxRetries`() {
            val properties3 = createRetryProperties(maxRetries = 3)
            val properties5 = createRetryProperties(maxRetries = 5)
            val properties10 = createRetryProperties(maxRetries = 10)

            val policy3 = OutboxRetryPolicyFactory.createDefault("fixed", properties3)
            val policy5 = OutboxRetryPolicyFactory.createDefault("fixed", properties5)
            val policy10 = OutboxRetryPolicyFactory.createDefault("fixed", properties10)

            assertThat(policy3.maxRetries()).isEqualTo(3)
            assertThat(policy5.maxRetries()).isEqualTo(5)
            assertThat(policy10.maxRetries()).isEqualTo(10)
        }

        @Test
        fun `should create multiple policies independently`() {
            val properties = createRetryProperties()

            val policy1 = OutboxRetryPolicyFactory.createDefault("fixed", properties)
            val policy2 = OutboxRetryPolicyFactory.createDefault("exponential", properties)

            assertThat(policy1).isInstanceOf(FixedDelayRetryPolicy::class.java)
            assertThat(policy2).isInstanceOf(ExponentialBackoffRetryPolicy::class.java)
            assertThat(policy1).isNotSameAs(policy2)
        }

        @Test
        fun `should handle recursive jittered creation with exponential base`() {
            val properties =
                createRetryProperties(
                    jitteredBasePolicy = "exponential",
                    jitteredJitter = 100L,
                    exponentialInitialDelay = 500L,
                    exponentialMaxDelay = 30000L,
                    exponentialMultiplier = 2.0,
                )

            val policy = OutboxRetryPolicyFactory.createDefault("jittered", properties)

            assertThat(policy).isInstanceOf(JitteredRetryPolicy::class.java)
            // Jitter wraps exponential, so delay should be exponential + jitter
            val delay1 = policy.nextDelay(1)
            val delay2 = policy.nextDelay(2)
            assertThat(delay1).isBetween(Duration.ofMillis(500), Duration.ofMillis(600))
            assertThat(delay2).isBetween(Duration.ofMillis(1000), Duration.ofMillis(1100))
        }
    }

    // Helper method to create retry properties with defaults
    private fun createRetryProperties(
        maxRetries: Int = 3,
        fixedDelay: Long = 5000L,
        exponentialInitialDelay: Long = 1000L,
        exponentialMaxDelay: Long = 300000L,
        exponentialMultiplier: Double = 2.0,
        jitteredBasePolicy: String = "fixed",
        jitteredJitter: Long = 500L,
    ): OutboxProperties.Retry {
        val retry = OutboxProperties.Retry()
        retry.maxRetries = maxRetries
        retry.policy = "exponential"

        val fixed = OutboxProperties.Retry.FixedRetry()
        fixed.delay = fixedDelay
        retry.fixed = fixed

        val exponential = OutboxProperties.Retry.ExponentialRetry()
        exponential.initialDelay = exponentialInitialDelay
        exponential.maxDelay = exponentialMaxDelay
        exponential.multiplier = exponentialMultiplier
        retry.exponential = exponential

        val jittered = OutboxProperties.Retry.JitteredRetry()
        jittered.basePolicy = jitteredBasePolicy
        jittered.jitter = jitteredJitter
        retry.jittered = jittered

        return retry
    }
}
