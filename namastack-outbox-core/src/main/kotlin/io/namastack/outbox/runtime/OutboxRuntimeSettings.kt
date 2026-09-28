package io.namastack.outbox.runtime

import java.time.Duration

/**
 * Programmatic settings for one isolated outbox runtime.
 *
 * Unlike the Spring configuration-properties model, this type contains only effective runtime
 * settings. It is suitable for callers that assemble runtimes directly.
 *
 * @property polling Polling behavior
 * @property retry Default retry behavior
 * @property processing Record processing behavior
 * @property instance Instance coordination behavior
 * @property multicaster Spring event multicaster behavior
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
data class OutboxRuntimeSettings(
    val polling: Polling = Polling(),
    val retry: Retry = Retry(),
    val processing: Processing = Processing(),
    val instance: Instance = Instance(),
    val multicaster: Multicaster = Multicaster(),
) {
    /**
     * @property batchSize Maximum number of record keys processed in one batch
     * @property trigger Polling trigger strategy (`fixed` or `adaptive`)
     * @property fixed Fixed polling settings
     * @property adaptive Adaptive polling settings
     */
    data class Polling(
        val batchSize: Int = 10,
        val trigger: String = "fixed",
        val fixed: FixedPolling = FixedPolling(),
        val adaptive: AdaptivePolling = AdaptivePolling(),
    )

    /** @property interval Fixed interval between polling cycles. */
    data class FixedPolling(
        val interval: Duration = Duration.ofSeconds(2),
    )

    /**
     * @property minInterval Minimum interval between polling cycles
     * @property maxInterval Maximum interval between polling cycles
     */
    data class AdaptivePolling(
        val minInterval: Duration = Duration.ofSeconds(1),
        val maxInterval: Duration = Duration.ofSeconds(8),
    )

    /**
     * @property maxRetries Maximum number of retry attempts
     * @property policy Retry policy (`fixed`, `linear`, or `exponential`)
     * @property fixed Fixed retry settings
     * @property linear Linear retry settings
     * @property exponential Exponential retry settings
     * @property jitter Maximum jitter added to or subtracted from a retry delay
     * @property includeExceptions Fully qualified names of exceptions to retry
     * @property excludeExceptions Fully qualified names of exceptions that must not be retried
     */
    data class Retry(
        val maxRetries: Int = 3,
        val policy: String = "exponential",
        val fixed: FixedRetry = FixedRetry(),
        val linear: LinearRetry = LinearRetry(),
        val exponential: ExponentialRetry = ExponentialRetry(),
        val jitter: Duration = Duration.ZERO,
        val includeExceptions: Set<String> = emptySet(),
        val excludeExceptions: Set<String> = emptySet(),
    ) {
        /** @property delay Fixed delay between retries. */
        data class FixedRetry(
            val delay: Duration = Duration.ofSeconds(5),
        )

        /**
         * @property initialDelay Initial delay
         * @property increment Delay added after each attempt
         * @property maxDelay Maximum delay
         */
        data class LinearRetry(
            val initialDelay: Duration = Duration.ofSeconds(2),
            val increment: Duration = Duration.ofSeconds(2),
            val maxDelay: Duration = Duration.ofMinutes(1),
        )

        /**
         * @property initialDelay Initial delay
         * @property multiplier Exponential delay multiplier
         * @property maxDelay Maximum delay
         */
        data class ExponentialRetry(
            val initialDelay: Duration = Duration.ofSeconds(2),
            val multiplier: Double = 2.0,
            val maxDelay: Duration = Duration.ofMinutes(1),
        )
    }

    /**
     * @property stopOnFirstFailure Whether batch processing stops after the first failure
     * @property deleteCompletedRecords Whether successfully processed records are deleted
     * @property executorCorePoolSize Core size of a platform-thread executor
     * @property executorMaxPoolSize Maximum size of a platform-thread executor
     * @property executorConcurrencyLimit Concurrency limit of a virtual-thread executor
     * @property shutdownTimeout Maximum time to wait for processing during shutdown
     */
    data class Processing(
        val stopOnFirstFailure: Boolean = true,
        val deleteCompletedRecords: Boolean = false,
        val executorCorePoolSize: Int = 4,
        val executorMaxPoolSize: Int = 8,
        val executorConcurrencyLimit: Int = -1,
        val shutdownTimeout: Duration = Duration.ofSeconds(30),
    )

    /**
     * @property heartbeatInterval Interval between instance heartbeats
     * @property staleInstanceTimeout Time after which an instance is considered stale
     * @property gracefulShutdownTimeout Propagation window before removing a stopping instance
     * @property rebalanceInterval Interval between partition rebalancing attempts
     */
    data class Instance(
        val heartbeatInterval: Duration = Duration.ofSeconds(5),
        val staleInstanceTimeout: Duration = Duration.ofSeconds(30),
        val gracefulShutdownTimeout: Duration = Duration.ZERO,
        val rebalanceInterval: Duration = Duration.ofSeconds(10),
    )

    /**
     * @property enabled Whether annotated application events are routed through this runtime
     * @property publishAfterSave Whether saved events are also published to Spring listeners
     */
    data class Multicaster(
        val enabled: Boolean = true,
        val publishAfterSave: Boolean = true,
    )
}
