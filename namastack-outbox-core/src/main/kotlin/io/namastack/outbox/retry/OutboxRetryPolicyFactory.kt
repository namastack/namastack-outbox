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
internal object OutboxRetryPolicyFactory {
    /**
     * Creates a pre-configured retry policy builder based on application properties.
     *
     * This method constructs a Builder with configuration applied in the following order:
     * 1. Maximum retry attempts
     * 2. Delay strategy configuration (fixed, linear, or exponential)
     * 3. Jitter configuration (if specified)
     * 4. Retry predicate configuration (include/exclude exceptions)
     *
     * The builder can then be further customized before calling `build()` to create
     * the final OutboxRetryPolicy instance.
     *
     * @param retryProperties Configuration properties for retry behavior from application settings
     * @return A configured Builder instance ready to build or further customize
     * @throws IllegalStateException if the policy name is unsupported or configuration is invalid
     */
    fun createDefault(retryProperties: OutboxProperties.Retry): OutboxRetryPolicy.Builder {
        val includeExceptions = convertExceptionNames(retryProperties.includeExceptions)
        val excludeExceptions = convertExceptionNames(retryProperties.excludeExceptions)

        return OutboxRetryPolicy
            .builder()
            .maxRetries(retryProperties.maxRetries)
            .let { configureDelay(retryProperties, it) }
            .retryOn(includeExceptions)
            .noRetryOn(excludeExceptions)
    }

    /**
     * Configures the delay strategy based on the policy name from properties.
     *
     * Supported delay strategies:
     * - **fixed**: Constant delay between retry attempts
     * - **linear**: Linearly increasing delay with configurable increment
     * - **exponential**: Exponentially increasing delay with configurable multiplier
     * - **jittered**: (Deprecated) Adds random jitter to a base policy. Use the `jitter` property instead.
     *
     * Jitter can be applied to any base policy (fixed, linear, or exponential) via the `jitter` property
     * in the respective configuration section. This adds randomness to prevent the thundering herd problem.
     *
     * @param retryProperties Configuration properties containing policy name and delay settings
     * @param builder The builder instance to configure
     * @return The builder with configured delay strategy
     * @throws IllegalStateException if the policy name or jittered base policy is unsupported
     */
    @Suppress("DEPRECATION")
    private fun configureDelay(
        retryProperties: OutboxProperties.Retry,
        builder: OutboxRetryPolicy.Builder,
    ): OutboxRetryPolicy.Builder {
        val name = retryProperties.policy

        return when (name.lowercase()) {
            "fixed" -> {
                builder
                    .fixedBackOff(
                        delay = Duration.ofMillis(retryProperties.fixed.delay),
                    ).jitter(jitter = Duration.ofMillis(retryProperties.jitter))
            }

            "linear" -> {
                builder
                    .linearBackoff(
                        initialDelay = Duration.ofMillis(retryProperties.linear.initialDelay),
                        increment = Duration.ofMillis(retryProperties.linear.increment),
                        maxDelay = Duration.ofMillis(retryProperties.linear.maxDelay),
                    ).jitter(jitter = Duration.ofMillis(retryProperties.jitter))
            }

            "exponential" -> {
                builder
                    .exponentialBackoff(
                        initialDelay = Duration.ofMillis(retryProperties.exponential.initialDelay),
                        multiplier = retryProperties.exponential.multiplier,
                        maxDelay = Duration.ofMillis(retryProperties.exponential.maxDelay),
                    ).jitter(jitter = Duration.ofMillis(retryProperties.jitter))
            }

            "jittered" -> {
                val basePolicy = retryProperties.jittered.basePolicy

                when (basePolicy.lowercase()) {
                    "fixed" -> {
                        builder
                            .fixedBackOff(
                                delay = Duration.ofMillis(retryProperties.fixed.delay),
                            ).jitter(jitter = Duration.ofMillis(retryProperties.jittered.jitter))
                    }

                    "linear" -> {
                        builder
                            .linearBackoff(
                                initialDelay = Duration.ofMillis(retryProperties.linear.initialDelay),
                                increment = Duration.ofMillis(retryProperties.linear.increment),
                                maxDelay = Duration.ofMillis(retryProperties.linear.maxDelay),
                            ).jitter(jitter = Duration.ofMillis(retryProperties.jittered.jitter))
                    }

                    "exponential" -> {
                        builder
                            .exponentialBackoff(
                                initialDelay = Duration.ofMillis(retryProperties.exponential.initialDelay),
                                maxDelay = Duration.ofMillis(retryProperties.exponential.maxDelay),
                                multiplier = retryProperties.exponential.multiplier,
                            ).jitter(jitter = Duration.ofMillis(retryProperties.jittered.jitter))
                    }

                    else -> error("Unsupported jittered base policy: $basePolicy")
                }
            }

            else -> error("Unsupported retry-policy: $name")
        }
    }

    /**
     * Converts fully qualified exception class names to Kotlin class references.
     *
     * This method resolves string class names (e.g., "java.lang.IllegalStateException")
     * to their corresponding Kotlin class references for use in retry predicate matching.
     *
     * @param exceptionNames Set of fully qualified exception class names
     * @return Set of Kotlin class references for the specified exception types
     * @throws IllegalStateException if any class name cannot be found in the classpath
     * @throws IllegalStateException if any class name refers to a non-Throwable type
     */
    private fun convertExceptionNames(exceptionNames: Set<String>): Set<Class<out Throwable>> =
        exceptionNames
            .map { className ->
                try {
                    Class.forName(className).asSubclass(Throwable::class.java)
                } catch (_: ClassNotFoundException) {
                    error("Exception class not found: $className")
                } catch (_: ClassCastException) {
                    error("Class $className is not a Throwable")
                }
            }.toSet()
}
