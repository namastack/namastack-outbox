package io.namastack.outbox.runtime

import io.namastack.outbox.OutboxProperties

/** Converts Spring-bound properties into effective immutable runtime settings. */
@Suppress("DEPRECATION")
internal fun OutboxProperties.toRuntimeSettings(): OutboxRuntimeSettings =
    OutboxRuntimeSettings(
        polling =
            OutboxRuntimeSettings.Polling(
                batchSize = batchSize ?: polling.batchSize,
                trigger = polling.trigger,
                fixed =
                    OutboxRuntimeSettings.FixedPolling(
                        interval = pollInterval ?: polling.fixed.interval,
                    ),
                adaptive =
                    OutboxRuntimeSettings.AdaptivePolling(
                        minInterval = polling.adaptive.minInterval,
                        maxInterval = polling.adaptive.maxInterval,
                    ),
            ),
        retry = retry.toRuntimeSettings(),
        processing =
            OutboxRuntimeSettings.Processing(
                stopOnFirstFailure = processing.stopOnFirstFailure,
                deleteCompletedRecords = processing.deleteCompletedRecords,
                executorCorePoolSize = processing.executorCorePoolSize,
                executorMaxPoolSize = processing.executorMaxPoolSize,
                executorConcurrencyLimit = processing.executorConcurrencyLimit,
                shutdownTimeout = processing.effectiveShutdownTimeout,
            ),
        instance =
            OutboxRuntimeSettings.Instance(
                heartbeatInterval = instance.effectiveHeartbeatInterval,
                staleInstanceTimeout = instance.effectiveStaleInstanceTimeout,
                gracefulShutdownTimeout = instance.effectiveGracefulShutdownTimeout,
                rebalanceInterval = rebalanceInterval ?: instance.rebalanceInterval,
            ),
        multicaster =
            OutboxRuntimeSettings.Multicaster(
                enabled = multicaster.enabled,
                publishAfterSave = processing.publishAfterSave ?: multicaster.publishAfterSave,
            ),
    )

internal fun OutboxProperties.Retry.toRuntimeSettings(): OutboxRuntimeSettings.Retry =
    OutboxRuntimeSettings.Retry(
        maxRetries = maxRetries,
        policy = policy,
        fixed = OutboxRuntimeSettings.Retry.FixedRetry(fixed.delay),
        linear =
            OutboxRuntimeSettings.Retry.LinearRetry(
                initialDelay = linear.initialDelay,
                increment = linear.increment,
                maxDelay = linear.maxDelay,
            ),
        exponential =
            OutboxRuntimeSettings.Retry.ExponentialRetry(
                initialDelay = exponential.initialDelay,
                multiplier = exponential.multiplier,
                maxDelay = exponential.maxDelay,
            ),
        jitter = jitter,
        includeExceptions = includeExceptions,
        excludeExceptions = excludeExceptions,
    )
