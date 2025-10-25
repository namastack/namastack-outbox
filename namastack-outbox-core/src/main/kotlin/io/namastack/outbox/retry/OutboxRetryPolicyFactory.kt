package io.namastack.outbox.retry

import io.namastack.outbox.OutboxProperties
import java.time.Duration

/**
 * Factory for creating retry policy instances based on configuration.
 *
 * This factory creates appropriate retry policy implementations based on the
 * policy name and configuration properties.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
object OutboxRetryPolicyFactory {
    /**
     * Creates a retry policy instance based on the given name and properties.
     *
     * @param name The name of the retry policy ("fixed", "exponential", "jittered")
     * @param retryProperties Configuration properties for retry behavior
     * @return A configured retry policy instance
     * @throws IllegalStateException if the policy name is unsupported or configuration is invalid
     */
    fun create(
        name: String,
        retryProperties: OutboxProperties.Retry,
    ): OutboxRetryPolicy =
        when (name.lowercase()) {
            "fixed" -> FixedDelayRetryPolicy(Duration.ofMillis(retryProperties.fixed.delay))

            "exponential" ->
                ExponentialBackoffRetryPolicy(
                    initialDelay = Duration.ofMillis(retryProperties.exponential.initialDelay),
                    maxDelay = Duration.ofMillis(retryProperties.exponential.maxDelay),
                    backoffMultiplier = retryProperties.exponential.multiplier,
                )

            "jittered" -> {
                val basePolicy = retryProperties.jittered.basePolicy

                if (basePolicy.lowercase() == "jittered") {
                    error("Cannot create a jittered policy with jittered base policy.")
                }

                JitteredRetryPolicy(
                    basePolicy = create(name = basePolicy, retryProperties = retryProperties),
                    jitter = Duration.ofMillis(retryProperties.jittered.jitter),
                )
            }

            else -> error("Unsupported retry-policy: $name")
        }
}
