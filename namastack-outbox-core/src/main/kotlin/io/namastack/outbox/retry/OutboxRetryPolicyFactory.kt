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
     * @param retryProperties Configuration properties for retry behavior
     * @return A configured retry policy instance
     * @throws IllegalStateException if the policy name is unsupported or configuration is invalid
     */
    fun createDefault(retryProperties: OutboxProperties.Retry): OutboxRetryPolicy.Builder =
        OutboxRetryPolicy
            .builder()
            .let { configureDelay(retryProperties, it) }
            .maxRetries(retryProperties.maxRetries)
            .let { configureRetryPredicate(retryProperties, it) }

    private fun configureDelay(
        retryProperties: OutboxProperties.Retry,
        builder: OutboxRetryPolicy.Builder,
    ): OutboxRetryPolicy.Builder {
        val name = retryProperties.policy

        return when (name.lowercase()) {
            "fixed" -> {
                builder.fixedDelay(
                    delay = Duration.ofMillis(retryProperties.fixed.delay),
                )
            }

            "exponential" -> {
                builder.exponentialBackoffDelay(
                    initialDelay = Duration.ofMillis(retryProperties.exponential.initialDelay),
                    maxDelay = Duration.ofMillis(retryProperties.exponential.maxDelay),
                    backoffMultiplier = retryProperties.exponential.multiplier,
                )
            }

            "jittered" -> {
                val basePolicy = retryProperties.jittered.basePolicy

                when (basePolicy.lowercase()) {
                    "fixed" -> {
                        builder.fixedDelay(
                            delay = Duration.ofMillis(retryProperties.fixed.delay),
                            jitter = Duration.ofMillis(retryProperties.jittered.jitter),
                        )
                    }

                    "exponential" -> {
                        builder.exponentialBackoffDelay(
                            initialDelay = Duration.ofMillis(retryProperties.exponential.initialDelay),
                            maxDelay = Duration.ofMillis(retryProperties.exponential.maxDelay),
                            backoffMultiplier = retryProperties.exponential.multiplier,
                            jitter = Duration.ofMillis(retryProperties.jittered.jitter),
                        )
                    }

                    else -> error("Unsupported jittered base policy: $basePolicy")
                }
            }

            else -> error("Unsupported retry-policy: $name")
        }
    }

    private fun configureRetryPredicate(
        retryProperties: OutboxProperties.Retry,
        builder: OutboxRetryPolicy.Builder,
    ): OutboxRetryPolicy.Builder {
        val includeExceptions = convertExceptionNames(retryProperties.includeExceptions)
        val excludeExceptions = convertExceptionNames(retryProperties.excludeExceptions)

        return builder
            .shouldRetry { exception ->
                when {
                    includeExceptions.isNotEmpty() -> includeExceptions.any { it.java.isInstance(exception) }
                    excludeExceptions.isNotEmpty() -> excludeExceptions.none { it.java.isInstance(exception) }
                    else -> true
                }
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
