package com.beisel.springoutbox.retry

import com.beisel.springoutbox.OutboxProperties
import java.time.Duration

object OutboxRetryPolicyFactory {
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
