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
    ): OutboxRetryPolicy {
        if (name.lowercase() == "jittered") {
            return createDeprecatedJitteredPolicy(retryProperties)
        }

        val config = resolveBackOffConfig(name, retryProperties)

        return OutboxRetryPolicy
            .builder()
            .maxRetries(retryProperties.maxRetries)
            .backOff(config.strategy)
            .retryOn(*resolveExceptions(retryProperties.includeExceptions))
            .noRetryOn(*resolveExceptions(retryProperties.excludeExceptions))
            .apply { if (config.jitter > 0) withJitter(Duration.ofMillis(config.jitter)) }
            .build()
    }

    private fun resolveBackOffConfig(
        name: String,
        retryProperties: OutboxProperties.Retry,
    ): BackOffConfig =
        when (name.lowercase()) {
            "fixed" -> {
                BackOffConfig(
                    strategy = BackOffStrategies.fixed(Duration.ofMillis(retryProperties.fixed.delay)),
                    jitter = retryProperties.fixed.jitter,
                )
            }

            "exponential" -> {
                BackOffConfig(
                    strategy =
                        BackOffStrategies.exponential(
                            initialDelay = Duration.ofMillis(retryProperties.exponential.initialDelay),
                            multiplier = retryProperties.exponential.multiplier,
                            maxDelay = Duration.ofMillis(retryProperties.exponential.maxDelay),
                        ),
                    jitter = retryProperties.exponential.jitter,
                )
            }

            "linear" -> {
                BackOffConfig(
                    strategy =
                        BackOffStrategies.linear(
                            initialDelay = Duration.ofMillis(retryProperties.linear.initialDelay),
                            increment = Duration.ofMillis(retryProperties.linear.increment),
                            maxDelay =
                                retryProperties.linear.maxDelay
                                    .takeIf { it > 0 }
                                    ?.let { Duration.ofMillis(it) },
                        ),
                    jitter = retryProperties.linear.jitter,
                )
            }

            else -> {
                error("Unsupported retry-policy: $name")
            }
        }

    private fun createDeprecatedJitteredPolicy(retryProperties: OutboxProperties.Retry): OutboxRetryPolicy {
        val jitteredConfig = retryProperties.jittered
        val basePolicy = jitteredConfig.basePolicy

        require(basePolicy.lowercase() != "jittered") {
            "Cannot create a jittered policy with jittered base policy."
        }

        val backOffConfig = resolveBackOffConfig(name = basePolicy, retryProperties = retryProperties)

        return OutboxRetryPolicy
            .builder()
            .backOff(backOffConfig.strategy)
            .withJitter(Duration.ofMillis(jitteredConfig.jitter))
            .build()
    }

    private fun resolveExceptions(exceptionNames: Set<String>): Array<KClass<out Throwable>> =
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
            }.toTypedArray()

    private data class BackOffConfig(
        val strategy: BackOffStrategy,
        val jitter: Long,
    )
}
