package io.namastack.outbox

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for Outbox functionality.
 *
 * This class defines all configurable aspects of the outbox pattern implementation,
 * including retry policies, processing behavior, and instance management.
 *
 * @param enabled Whether the outbox functionality is enabled. Defaults to true.
 *                Set to false to disable outbox auto-configuration entirely.
 * @param pollInterval Interval in milliseconds at which the outbox is polled
 * @param rebalanceInterval Interval in milliseconds at which partition rebalancing is performed
 * @param batchSize Maximum number of record keys to process in a single batch
 * @param retry Configuration for retry mechanisms
 * @param processing Configuration for record processing behavior
 * @param instance Configuration for instance management and coordination
 * @param multicaster Configuration for the custom application event multicaster
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@ConfigurationProperties(prefix = "namastack.outbox")
data class OutboxProperties(
    var enabled: Boolean = true,
    var pollInterval: Long = 2000,
    var rebalanceInterval: Long = 10000,
    var batchSize: Int = 10,
    var retry: Retry = Retry(),
    var processing: Processing = Processing(),
    var instance: Instance = Instance(),
    var multicaster: Multicaster = Multicaster(),
) {
    /**
     * Configuration for outbox record processing behavior.
     *
     * @param stopOnFirstFailure Whether to stop processing on the first failure
     * @param publishAfterSave Whether to publish events to listeners after saving to outbox
     * @param deleteCompletedRecords Whether to delete completed records after processing
     * @param executorCorePoolSize Core pool size for the processing executor
     * @param executorMaxPoolSize Maximum pool size for the processing executor
     * @param executorConcurrencyLimit Concurrency limit for the virtual thread executor (-1 for no limit)
     */
    data class Processing(
        var stopOnFirstFailure: Boolean = true,
        var publishAfterSave: Boolean = true,
        var deleteCompletedRecords: Boolean = false,
        var executorCorePoolSize: Int = 4,
        var executorMaxPoolSize: Int = 8,
        var executorConcurrencyLimit: Int = -1,
    )

    /**
     * Configuration for instance management and coordination.
     *
     * @param gracefulShutdownTimeoutSeconds Timeout in seconds for graceful shutdown
     * @param staleInstanceTimeoutSeconds Timeout in seconds to consider an instance stale
     * @param heartbeatIntervalSeconds Interval in seconds between heartbeats
     */
    data class Instance(
        var gracefulShutdownTimeoutSeconds: Long = 15,
        var staleInstanceTimeoutSeconds: Long = 30,
        var heartbeatIntervalSeconds: Long = 5,
    )

    /**
     * Configuration for the custom application event multicaster.
     *
     * Controls the OutboxEventMulticaster bean that intercepts @OutboxEvent annotated events
     * and routes them through the outbox for reliable delivery.
     *
     * @param enabled Whether to enable the custom application event multicaster for @OutboxEvent handling.
     */
    data class Multicaster(
        var enabled: Boolean = true,
    )

    /**
     * Configuration for retry policies and behavior.
     *
     * @param maxRetries Maximum number of retry attempts
     * @param policy Name of the retry policy to use ("fixed", "linear", "exponential")
     * @param fixed Configuration for fixed delay retry
     * @param linear Configuration for linear backoff retry
     * @param exponential Configuration for exponential backoff retry
     * @param jitter Maximum jitter in milliseconds to add or subtract from each delay (0 = no jitter)
     * @param includeExceptions Fully qualified class names of exceptions to retry on
     * @param excludeExceptions Fully qualified class names of exceptions to exclude from retry
     */
    data class Retry(
        var maxRetries: Int = 3,
        var policy: String = "exponential",
        var fixed: FixedRetry = FixedRetry(),
        var linear: LinearRetry = LinearRetry(),
        var exponential: ExponentialRetry = ExponentialRetry(),
        var jitter: Long = 0,
        var includeExceptions: Set<String> = emptySet(),
        var excludeExceptions: Set<String> = emptySet(),
    ) {
        /**
         * Configuration for fixed delay retry policy.
         *
         * @param delay Fixed delay in milliseconds between retries
         */
        data class FixedRetry(
            var delay: Long = 5000,
        )

        /**
         * Configuration for linear backoff retry policy.
         *
         * @param initialDelay Initial delay in milliseconds
         * @param increment Amount to add in milliseconds for each subsequent retry
         * @param maxDelay Maximum delay in milliseconds
         */
        data class LinearRetry(
            var initialDelay: Long = 2000,
            var increment: Long = 2000,
            var maxDelay: Long = 60000,
        )

        /**
         * Configuration for exponential backoff retry policy.
         *
         * @param initialDelay Initial delay in milliseconds
         * @param multiplier Multiplier for exponential backoff
         * @param maxDelay Maximum delay in milliseconds
         */
        data class ExponentialRetry(
            var initialDelay: Long = 2000,
            var multiplier: Double = 2.0,
            var maxDelay: Long = 60000,
        )
    }
}
