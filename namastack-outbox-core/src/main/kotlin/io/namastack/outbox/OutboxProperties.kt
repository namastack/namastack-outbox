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
 * @param batchSize Maximum number of records to process in a single batch
 * @param retry Configuration for retry mechanisms
 * @param processing Configuration for record processing behavior
 * @param instance Configuration for instance management and coordination
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
@ConfigurationProperties(prefix = "outbox")
data class OutboxProperties(
    var enabled: Boolean = true,
    var pollInterval: Long = 2000,
    var rebalanceInterval: Long = 10000,
    var batchSize: Int = 10,
    var retry: Retry = Retry(),
    var processing: Processing = Processing(),
    var instance: Instance = Instance(),
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
     * Configuration for retry policies and behavior.
     *
     * @param maxRetries Maximum number of retry attempts
     * @param policy Name of the retry policy to use ("fixed", "linear", "exponential", "jittered")
     * @param includeExceptions Fully qualified class names of exceptions to retry on
     * @param excludeExceptions Fully qualified class names of exceptions to exclude from retry
     * @param fixed Configuration for fixed delay retry
     * @param linear Configuration for linear backoff retry
     * @param exponential Configuration for exponential backoff retry
     * @param jittered Configuration for jittered retry (deprecated, use jitter property instead)
     */
    data class Retry(
        var maxRetries: Int = 3,
        var policy: String = "exponential",
        var includeExceptions: Set<String> = emptySet(),
        var excludeExceptions: Set<String> = emptySet(),
        var fixed: FixedRetry = FixedRetry(),
        var linear: LinearRetry = LinearRetry(),
        var exponential: ExponentialRetry = ExponentialRetry(),
        @Deprecated("Use jitter property in fixed, linear or exponential retry configuration instead")
        var jittered: JitteredRetry = JitteredRetry(),
    ) {
        /**
         * Configuration for fixed delay retry policy.
         *
         * @param delay Fixed delay in milliseconds between retries
         * @param jitter Maximum jitter in milliseconds to add to each delay (0 = no jitter)
         */
        data class FixedRetry(
            var delay: Long = 5000,
            var jitter: Long = 0,
        )

        /**
         * Configuration for linear backoff retry policy.
         *
         * @param initialDelay Initial delay in milliseconds
         * @param increment Amount to add in milliseconds for each subsequent retry
         * @param maxDelay Maximum delay in milliseconds
         * @param jitter Maximum jitter in milliseconds to add to each delay (0 = no jitter)
         */
        data class LinearRetry(
            var initialDelay: Long = 2000,
            var increment: Long = 2000,
            var maxDelay: Long = 60000,
            var jitter: Long = 0,
        )

        /**
         * Configuration for exponential backoff retry policy.
         *
         * @param initialDelay Initial delay in milliseconds
         * @param multiplier Multiplier for exponential backoff
         * @param maxDelay Maximum delay in milliseconds
         * @param jitter Maximum jitter in milliseconds to add to each delay (0 = no jitter)
         */
        data class ExponentialRetry(
            var initialDelay: Long = 2000,
            var multiplier: Double = 2.0,
            var maxDelay: Long = 60000,
            var jitter: Long = 0,
        )

        /**
         * Configuration for jittered retry policy.
         *
         * @param basePolicy Base retry policy to apply jitter to
         * @param jitter Maximum jitter amount in milliseconds
         * @deprecated Use jitter property in fixed, linear or exponential retry configuration instead
         */
        @Deprecated("Use jitter property in fixed, linear or exponential retry configuration instead")
        data class JitteredRetry(
            var basePolicy: String = "exponential",
            var jitter: Long = 500,
        )
    }
}
