package io.namastack.outbox

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Configuration properties for Outbox functionality.
 *
 * This class defines all configurable aspects of the outbox pattern implementation,
 * including retry policies, processing behavior, and instance management.
 *
 * @param enabled Whether the outbox functionality is enabled. Defaults to true.
 *                Set to false to disable outbox auto-configuration entirely.
 * @param pollInterval Interval at which the outbox is polled (deprecated)
 * @param rebalanceInterval Interval at which partition rebalancing is performed (deprecated)
 * @param batchSize Maximum number of record keys to process in a single batch (deprecated)
 * @param polling Configuration for polling behavior
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
    @Deprecated("Use polling.fixed.interval or polling.adaptive.minInterval/maxInterval instead")
    var pollInterval: Duration? = null,
    @Deprecated("Use instance.rebalanceInterval instead")
    var rebalanceInterval: Duration? = null,
    @Deprecated("Use polling.batchsize instead")
    var batchSize: Int? = null,
    var polling: Polling = Polling(),
    var retry: Retry = Retry(),
    var processing: Processing = Processing(),
    var instance: Instance = Instance(),
    var multicaster: Multicaster = Multicaster(),
) {
    /**
     * Configuration for polling behavior.
     *
     * @param batchSize Maximum number of record keys to process in a single batch
     * @param trigger The polling trigger strategy to use ("fixed" or "adaptive")
     * @param fixed Configuration for fixed polling strategy
     * @param adaptive Configuration for adaptive polling strategy
     */
    data class Polling(
        var batchSize: Int = 10,
        var trigger: String = "fixed",
        var fixed: FixedPolling = FixedPolling(),
        var adaptive: AdaptivePolling = AdaptivePolling(),
    )

    /**
     * Configuration for fixed polling strategy.
     *
     * @param interval Fixed interval between polling cycles
     */
    data class FixedPolling(
        var interval: Duration = Duration.ofSeconds(2),
    )

    /**
     * Configuration for adaptive polling strategy.
     *
     * Adaptive polling adjusts the polling interval dynamically based on system activity.
     *
     * @param minInterval Minimum interval between polling cycles
     * @param maxInterval Maximum interval between polling cycles
     */
    data class AdaptivePolling(
        var minInterval: Duration = Duration.ofSeconds(1),
        var maxInterval: Duration = Duration.ofSeconds(8),
    )

    /**
     * Configuration for outbox record processing behavior.
     *
     * @param stopOnFirstFailure Whether to stop processing on the first failure
     * @param publishAfterSave Whether to publish events to listeners after saving to outbox (deprecated)
     * @param deleteCompletedRecords Whether to delete completed records after processing
     * @param executorCorePoolSize Core pool size for the processing executor
     * @param executorMaxPoolSize Maximum pool size for the processing executor
     * @param executorConcurrencyLimit Concurrency limit for the virtual thread executor (-1 for no limit)
     * @param shutdownTimeoutSeconds Maximum time in seconnds to wait for processing to complete during shutdown (default: 30)
     * @param shutdownTimeoutValue Maximum time to wait for processing to complete during shutdown (default: 30s)
     */
    data class Processing(
        var stopOnFirstFailure: Boolean = true,
        @Deprecated("Use multicaster.publishAfterSave instead")
        var publishAfterSave: Boolean? = null,
        var deleteCompletedRecords: Boolean = false,
        var executorCorePoolSize: Int = 4,
        var executorMaxPoolSize: Int = 8,
        var executorConcurrencyLimit: Int = -1,
        @Deprecated("Use shutdownTimeout instead")
        var shutdownTimeoutSeconds: Long? = null,
        private var shutdownTimeoutValue: Duration = Duration.ofSeconds(30),
    ) {
        var shutdownTimeout: Duration
            get() = shutdownTimeoutSeconds?.let(Duration::ofSeconds) ?: shutdownTimeoutValue
            set(value) {
                shutdownTimeoutValue = value
            }
    }

    /**
     * Configuration for instance management and coordination.
     *
     * @param heartbeatIntervalSeconds Interval in seconds between heartbeats
     * @param staleInstanceTimeoutSeconds Timeout in seconds to consider an instance stale
     * @param gracefulShutdownTimeoutSeconds Optional propagation window (in seconds) after marking an instance
     * @param heartbeatIntervalValue Interval between heartbeats
     * @param staleInstanceTimeoutValue Timeout to consider an instance stale
     * @param gracefulShutdownTimeoutValue Optional propagation window after marking an instance
     *        as shutting down before removing it from the registry. Default: 0 (disabled).
     * @param rebalanceInterval Interval at which partition rebalancing is performed
     */
    data class Instance(
        @Deprecated("Use heartbeatInterval instead")
        var heartbeatIntervalSeconds: Long? = null,
        @Deprecated("Use staleInstanceTimeout instead")
        var staleInstanceTimeoutSeconds: Long? = null,
        @Deprecated("Use gracefulShutdownTimeout instead")
        var gracefulShutdownTimeoutSeconds: Long? = null,
        private var heartbeatIntervalValue: Duration = Duration.ofSeconds(5),
        private var staleInstanceTimeoutValue: Duration = Duration.ofSeconds(30),
        private var gracefulShutdownTimeoutValue: Duration = Duration.ofSeconds(0),
        var rebalanceInterval: Duration = Duration.ofSeconds(10),
    ) {
        var heartbeatInterval: Duration
            get() = heartbeatIntervalSeconds?.let(Duration::ofSeconds) ?: heartbeatIntervalValue
            set(value) {
                heartbeatIntervalValue = value
            }
        var staleInstanceTimeout: Duration
            get() = staleInstanceTimeoutSeconds?.let(Duration::ofSeconds) ?: staleInstanceTimeoutValue
            set(value) {
                staleInstanceTimeoutValue = value
            }
        var gracefulShutdownTimeout: Duration
            get() = gracefulShutdownTimeoutSeconds?.let(Duration::ofSeconds) ?: gracefulShutdownTimeoutValue
            set(value) {
                gracefulShutdownTimeoutValue = value
            }
    }

    /**
     * Configuration for the custom application event multicaster.
     *
     * Controls the OutboxEventMulticaster bean that intercepts @OutboxEvent annotated events
     * and routes them through the outbox for reliable delivery.
     *
     * @param enabled Whether to enable the custom application event multicaster for @OutboxEvent handling.
     * @param publishAfterSave Whether to publish events to Spring listeners after saving them to the outbox.
     */
    data class Multicaster(
        var enabled: Boolean = true,
        var publishAfterSave: Boolean = true,
    )

    /**
     * Configuration for retry policies and behavior.
     *
     * @param maxRetries Maximum number of retry attempts
     * @param policy Name of the retry policy to use ("fixed", "linear", "exponential")
     * @param fixed Configuration for fixed delay retry
     * @param linear Configuration for linear backoff retry
     * @param exponential Configuration for exponential backoff retry
     * @param jitter Maximum jitter to add or subtract from each delay (0 = no jitter)
     * @param includeExceptions Fully qualified class names of exceptions to retry on
     * @param excludeExceptions Fully qualified class names of exceptions to exclude from retry
     */
    data class Retry(
        var maxRetries: Int = 3,
        var policy: String = "exponential",
        var fixed: FixedRetry = FixedRetry(),
        var linear: LinearRetry = LinearRetry(),
        var exponential: ExponentialRetry = ExponentialRetry(),
        var jitter: Duration = Duration.ofMillis(0),
        var includeExceptions: Set<String> = emptySet(),
        var excludeExceptions: Set<String> = emptySet(),
    ) {
        /**
         * Configuration for fixed delay retry policy.
         *
         * @param delay Fixed delay between retries
         */
        data class FixedRetry(
            var delay: Duration = Duration.ofSeconds(5),
        )

        /**
         * Configuration for linear backoff retry policy.
         *
         * @param initialDelay Initial delay
         * @param increment Amount to add for each subsequent retry
         * @param maxDelay Maximum delay
         */
        data class LinearRetry(
            var initialDelay: Duration = Duration.ofSeconds(2),
            var increment: Duration = Duration.ofSeconds(2),
            var maxDelay: Duration = Duration.ofMinutes(1),
        )

        /**
         * Configuration for exponential backoff retry policy.
         *
         * @param initialDelay Initial delay
         * @param multiplier Multiplier for exponential backoff
         * @param maxDelay Maximum delay
         */
        data class ExponentialRetry(
            var initialDelay: Duration = Duration.ofSeconds(2),
            var multiplier: Double = 2.0,
            var maxDelay: Duration = Duration.ofMinutes(1),
        )
    }
}
