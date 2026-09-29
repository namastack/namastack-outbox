package io.namastack.outbox.trigger

import io.namastack.outbox.runtime.OutboxRuntimeSettings
import java.time.Clock

/**
 * Factory for creating [OutboxPollingTrigger] instances based on effective runtime settings.
 *
 * This factory creates the appropriate trigger implementation based on the configured
 * polling strategy. It supports:
 * - [OutboxRuntimeSettings.Polling.Trigger.FIXED]: Creates a [FixedPollingTrigger] with constant delay
 * - [OutboxRuntimeSettings.Polling.Trigger.ADAPTIVE]: Creates an [AdaptivePollingTrigger] with dynamic delay adjustment
 *
 * @author Aleksander Zamojski
 * @since 1.1.0
 */
internal object OutboxPollingTriggerFactory {
    /**
     * Creates an appropriate [OutboxPollingTrigger] based on the provided settings.
     *
     * The trigger type is determined by [OutboxRuntimeSettings.Polling.trigger].
     *
     * @param settings Effective polling settings
     * @param clock The clock to use for time calculations
     * @return The configured polling trigger
     */
    fun create(
        settings: OutboxRuntimeSettings.Polling,
        clock: Clock,
    ): OutboxPollingTrigger =
        when (settings.trigger) {
            OutboxRuntimeSettings.Polling.Trigger.FIXED -> {
                FixedPollingTrigger(
                    delay = settings.fixed.interval,
                    clock = clock,
                )
            }

            OutboxRuntimeSettings.Polling.Trigger.ADAPTIVE -> {
                AdaptivePollingTrigger(
                    minDelay = settings.adaptive.minInterval,
                    maxDelay = settings.adaptive.maxInterval,
                    batchSize = settings.batchSize,
                    clock = clock,
                )
            }
        }
}
