package io.namastack.outbox

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for Outbox functionality.
 *
 * This class defines all configurable aspects of the outbox pattern implementation,
 * including retry policies, processing behavior, and instance management.
 *
 * @param pollInterval Interval in milliseconds at which the outbox is polled
 * @param batchSize Maximum number of records to process in a single batch
 * @param retry Configuration for retry mechanisms
 * @param processing Configuration for record processing behavior
 * @param instance Configuration for instance management and coordination
 * @param schemaInitialization Configuration for database schema initialization
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@ConfigurationProperties(prefix = "outbox")
data class OutboxProperties(
    val pollInterval: Long = 2000,
    val rebalanceInterval: Long = 10000,
    val batchSize: Int = 10,
    val retry: Retry = Retry(),
    val processing: Processing = Processing(),
    val instance: Instance = Instance(),
    val schemaInitialization: SchemaInitialization = SchemaInitialization(),
) {
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
            val initialDelay: Long = 2000,
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
     * @param publishAfterSave Whether to publish events to listeners after saving to outbox
     */
    data class Processing(
        val stopOnFirstFailure: Boolean = true,
        val publishAfterSave: Boolean = true,
        val deleteCompletedRecords: Boolean = false,
        val executorCorePoolSize: Int = 4,
        val executorMaxPoolSize: Int = 8,
    )

    /**
     * Configuration for instance management and coordination.
     *
     * @param gracefulShutdownTimeoutSeconds Timeout in seconds for graceful shutdown
     * @param staleInstanceTimeoutSeconds Timeout in seconds to consider an instance stale
     * @param heartbeatIntervalSeconds Interval in seconds between heartbeats
     * @param newInstanceDetectionIntervalSeconds Interval in seconds for detecting new instances
     */
    data class Instance(
        val gracefulShutdownTimeoutSeconds: Long = 15,
        val staleInstanceTimeoutSeconds: Long = 30,
        val heartbeatIntervalSeconds: Long = 5,
        val newInstanceDetectionIntervalSeconds: Long = 10,
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
