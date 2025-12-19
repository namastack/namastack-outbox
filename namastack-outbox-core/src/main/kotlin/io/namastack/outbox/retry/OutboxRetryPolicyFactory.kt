package io.namastack.outbox.retry

import io.namastack.outbox.OutboxProperties
import java.time.Duration
import kotlin.reflect.KClass

/**
 * Factory for creating retry policy instances based on configuration.
 *
 * This factory creates appropriate retry policy implementations based on the
 * policy name and configuration properties.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
internal object OutboxRetryPolicyFactory {
    /**
     * Creates a retry policy instance based on the given name and properties.
     *
     * @param name The name of the retry policy ("fixed", "exponential", "jittered")
     * @param retryProperties Configuration properties for retry behavior
     * @return A configured retry policy instance
     * @throws IllegalStateException if the policy name is unsupported or configuration is invalid
     */
    fun createDefault(
        name: String,
        retryProperties: OutboxProperties.Retry,
    ): OutboxRetryPolicy =
        when (name.lowercase()) {
            "fixed" -> {
                FixedDelayRetryPolicy(
                    delay = Duration.ofMillis(retryProperties.fixed.delay),
                    maxRetries = retryProperties.maxRetries,
                    includeExceptions = convertExceptionNames(retryProperties.includeExceptions),
                    excludeExceptions = convertExceptionNames(retryProperties.excludeExceptions),
                )
            }

            "exponential" -> {
                ExponentialBackoffRetryPolicy(
                    initialDelay = Duration.ofMillis(retryProperties.exponential.initialDelay),
                    maxDelay = Duration.ofMillis(retryProperties.exponential.maxDelay),
                    backoffMultiplier = retryProperties.exponential.multiplier,
                    maxRetries = retryProperties.maxRetries,
                    includeExceptions = convertExceptionNames(retryProperties.includeExceptions),
                    excludeExceptions = convertExceptionNames(retryProperties.excludeExceptions),
                )
            }

            "jittered" -> {
                val basePolicy = retryProperties.jittered.basePolicy

                if (basePolicy.lowercase() == "jittered") {
                    error("Cannot create a jittered policy with jittered base policy.")
                }

                JitteredRetryPolicy(
                    basePolicy = createDefault(name = basePolicy, retryProperties = retryProperties),
                    jitter = Duration.ofMillis(retryProperties.jittered.jitter),
                )
            }

            else -> {
                error("Unsupported retry-policy: $name")
            }
        }

    private fun convertExceptionNames(exceptionNames: Set<String>): Set<KClass<out Throwable>> =
        exceptionNames
            .map { className ->
                try {
                    @Suppress("UNCHECKED_CAST")
                    Class.forName(className).kotlin as KClass<out Throwable>
                } catch (_: ClassNotFoundException) {
                    error("Exception class not found: $className")
                } catch (_: ClassCastException) {
                    error("Class $className is not a Throwable")
                }
            }.toSet()
}
