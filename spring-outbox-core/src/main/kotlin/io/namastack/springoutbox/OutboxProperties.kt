package io.namastack.springoutbox

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for Spring Outbox functionality.
 *
 * This class defines all configurable aspects of the outbox pattern implementation,
 * including locking, retry policies, and processing behavior.
 *
 * @param locking Configuration for outbox record locking
 * @param retry Configuration for retry mechanisms
 * @param processing Configuration for record processing behavior
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@ConfigurationProperties(prefix = "outbox")
data class OutboxProperties(
    val pollInterval: Long = 5000,
    val locking: Locking = Locking(),
    val retry: Retry = Retry(),
    val processing: Processing = Processing(),
    val schemaInitialization: SchemaInitialization = SchemaInitialization(),
) {
    /**
     * Configuration for outbox record locking mechanism.
     *
     * @param extensionSeconds Duration in seconds to extend a lock
     * @param refreshThreshold Threshold in seconds for refreshing locks
     */
    data class Locking(
        val extensionSeconds: Long = 5,
        val refreshThreshold: Long = 2,
    )

    /**
     * Configuration for retry policies and behavior.
     *
     * @param maxRetries Maximum number of retry attempts
     * @param policy Name of the retry policy to use ("exponential", "fixed", "jittered")
     * @param exponential Configuration for exponential backoff retry
     * @param fixed Configuration for fixed delay retry
     * @param jittered Configuration for jittered retry
     */
    data class Retry(
        val maxRetries: Int = 3,
        val policy: String = "exponential",
        val exponential: ExponentialRetry = ExponentialRetry(),
        val fixed: FixedRetry = FixedRetry(),
        val jittered: JitteredRetry = JitteredRetry(),
    ) {
        /**
         * Configuration for exponential backoff retry policy.
         *
         * @param initialDelay Initial delay in milliseconds
         * @param maxDelay Maximum delay in milliseconds
         * @param multiplier Multiplier for exponential backoff
         */
        data class ExponentialRetry(
            val initialDelay: Long = 1000,
            val maxDelay: Long = 60000,
            val multiplier: Double = 2.0,
        )

        /**
         * Configuration for fixed delay retry policy.
         *
         * @param delay Fixed delay in milliseconds between retries
         */
        data class FixedRetry(
            val delay: Long = 5000,
        )

        /**
         * Configuration for jittered retry policy.
         *
         * @param basePolicy Base retry policy to apply jitter to
         * @param jitter Maximum jitter amount in milliseconds
         */
        data class JitteredRetry(
            val basePolicy: String = "exponential",
            val jitter: Long = 500,
        )
    }

    /**
     * Configuration for outbox record processing behavior.
     *
     * @param stopOnFirstFailure Whether to stop processing on the first failure
     */
    data class Processing(
        val stopOnFirstFailure: Boolean = true,
    )

    /**
     * Configuration for database schema initialization.
     *
     * @param enabled Whether to enable automatic schema initialization
     */
    data class SchemaInitialization(
        val enabled: Boolean = true,
    )
}
