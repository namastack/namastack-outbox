package io.namastack.outbox.retry

import io.namastack.outbox.OutboxProperties
import io.namastack.outbox.runtime.OutboxRuntimeSettings
import io.namastack.outbox.runtime.toRuntimeSettings

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
     * Creates a pre-configured retry policy builder from programmatic runtime settings.
     *
     * @param retrySettings Runtime-local retry settings
     * @return A configured builder ready to build or customize
     * @throws IllegalStateException if the policy name or configuration is unsupported
     */
    fun createDefault(retrySettings: OutboxRuntimeSettings.Retry): OutboxRetryPolicy.Builder {
        val includeExceptions = convertExceptionNames(retrySettings.includeExceptions)
        val excludeExceptions = convertExceptionNames(retrySettings.excludeExceptions)

        return OutboxRetryPolicy
            .builder()
            .maxRetries(retrySettings.maxRetries)
            .let { configureDelay(retrySettings, it) }
            .retryOn(includeExceptions)
            .noRetryOn(excludeExceptions)
    }

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
    fun createDefault(retryProperties: OutboxProperties.Retry): OutboxRetryPolicy.Builder =
        createDefault(retryProperties.toRuntimeSettings())

    /**
     * Configures the delay strategy based on the policy name from properties.
     *
     * Supported delay strategies:
     * - **fixed**: Constant delay between retry attempts
     * - **linear**: Linearly increasing delay with configurable increment
     * - **exponential**: Exponentially increasing delay with configurable multiplier
     *
     * Jitter can be applied to any base policy (fixed, linear, or exponential) via the `jitter` property
     * in the retry configuration section. This adds randomness to prevent the thundering herd problem.
     *
     * @param retrySettings Runtime settings containing the policy name and delay configuration
     * @param builder The builder instance to configure
     * @return The builder with configured delay strategy
     * @throws IllegalStateException if the policy name is unsupported
     */
    private fun configureDelay(
        retrySettings: OutboxRuntimeSettings.Retry,
        builder: OutboxRetryPolicy.Builder,
    ): OutboxRetryPolicy.Builder {
        val name = retrySettings.policy

        return when (name.lowercase()) {
            "fixed" -> {
                builder
                    .fixedBackOff(
                        delay = retrySettings.fixed.delay,
                    ).jitter(jitter = retrySettings.jitter)
            }

            "linear" -> {
                builder
                    .linearBackoff(
                        initialDelay = retrySettings.linear.initialDelay,
                        increment = retrySettings.linear.increment,
                        maxDelay = retrySettings.linear.maxDelay,
                    ).jitter(jitter = retrySettings.jitter)
            }

            "exponential" -> {
                builder
                    .exponentialBackoff(
                        initialDelay = retrySettings.exponential.initialDelay,
                        multiplier = retrySettings.exponential.multiplier,
                        maxDelay = retrySettings.exponential.maxDelay,
                    ).jitter(jitter = retrySettings.jitter)
            }

            else -> {
                error("Unsupported retry-policy: $name")
            }
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
