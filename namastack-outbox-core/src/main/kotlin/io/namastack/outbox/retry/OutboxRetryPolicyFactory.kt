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
     * Creates a pre-configured retry policy builder based on application properties.
     *
     * This method constructs a Builder with configuration applied in the following order:
     * 1. Retry predicate configuration (include/exclude exceptions)
     * 2. Delay strategy configuration (fixed, exponential, or jittered)
     * 3. Maximum retry attempts
     *
     * The builder can then be further customized before calling `build()` to create
     * the final OutboxRetryPolicy instance.
     *
     * @param retryProperties Configuration properties for retry behavior from application settings
     * @return A configured Builder instance ready to build or further customize
     * @throws IllegalStateException if the policy name is unsupported or configuration is invalid
     * @see configureRetryPredicate
     * @see configureDelay
     */
    fun createDefault(retryProperties: OutboxProperties.Retry): OutboxRetryPolicy.Builder =
        OutboxRetryPolicy
            .builder()
            .let { configureRetryPredicate(retryProperties, it) }
            .let { configureDelay(retryProperties, it) }
            .maxRetries(retryProperties.maxRetries)

    /**
     * Configures the retry predicate based on include/exclude exception lists.
     *
     * The retry predicate determines which exceptions should trigger retry attempts:
     * - If `includeExceptions` is not empty: Only retries exceptions matching the include list
     * - If `excludeExceptions` is not empty: Retries all exceptions except those in the exclude list
     * - If both are empty: Retries all exceptions (default behavior)
     *
     * Note: `includeExceptions` takes precedence over `excludeExceptions` if both are configured.
     *
     * @param retryProperties Configuration properties containing exception include/exclude lists
     * @param builder The builder instance to configure
     * @return The builder with configured retry predicate
     * @throws IllegalStateException if exception class names cannot be resolved or are not Throwable types
     * @see convertExceptionNames
     */
    private fun configureRetryPredicate(
        retryProperties: OutboxProperties.Retry,
        builder: OutboxRetryPolicy.Builder,
    ): OutboxRetryPolicy.Builder {
        val includeExceptions = convertExceptionNames(retryProperties.includeExceptions)
        val excludeExceptions = convertExceptionNames(retryProperties.excludeExceptions)

        return builder
            .retryPredicate { exception ->
                when {
                    includeExceptions.isNotEmpty() -> includeExceptions.any { it.java.isInstance(exception) }
                    excludeExceptions.isNotEmpty() -> excludeExceptions.none { it.java.isInstance(exception) }
                    else -> true
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

    /**
     * Configures the delay strategy based on the policy name from properties.
     *
     * Supported delay strategies:
     * - **fixed**: Constant delay between retry attempts
     * - **exponential**: Exponentially increasing delay with configurable multiplier
     * - **jittered**: Adds random jitter to a base policy (fixed or exponential)
     *
     * For jittered policies, the base policy must be either "fixed" or "exponential".
     *
     * @param retryProperties Configuration properties containing policy name and delay settings
     * @param builder The builder instance to configure
     * @return The builder with configured delay strategy
     * @throws IllegalStateException if the policy name or jittered base policy is unsupported
     */
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
}
