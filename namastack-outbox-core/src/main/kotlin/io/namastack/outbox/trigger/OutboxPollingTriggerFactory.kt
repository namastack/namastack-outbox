package io.namastack.outbox.trigger

import io.namastack.outbox.OutboxProperties
import io.namastack.outbox.runtime.OutboxRuntimeSettings
import io.namastack.outbox.runtime.toRuntimeSettings
import java.time.Clock

/**
 * Factory for creating [OutboxPollingTrigger] instances based on effective runtime settings.
 *
 * This factory creates the appropriate trigger implementation based on the configured
 * polling strategy. It supports:
 * - "fixed": Creates a [FixedPollingTrigger] with constant delay
 * - "adaptive": Creates an [AdaptivePollingTrigger] with dynamic delay adjustment
 *
 * @author Aleksander Zamojski
 * @since 1.1.0
 */
internal object OutboxPollingTriggerFactory {
    /**
     * Creates an appropriate [OutboxPollingTrigger] based on the provided settings.
     *
     * The trigger type is determined by [OutboxRuntimeSettings.Polling.trigger]. Supported values:
     * - "fixed": Creates a fixed delay trigger
     * - "adaptive": Creates an adaptive delay trigger
     *
     * @param settings Effective polling settings
     * @param clock The clock to use for time calculations
     * @return The configured polling trigger
     * @throws IllegalStateException if an unsupported trigger type is specified
     */
    fun create(
        settings: OutboxRuntimeSettings.Polling,
        clock: Clock,
    ): OutboxPollingTrigger {
        val name = settings.trigger

        return when (name.lowercase()) {
            "fixed" -> {
                FixedPollingTrigger(
                    delay = settings.fixed.interval,
                    clock = clock,
                )
            }

            "adaptive" -> {
                AdaptivePollingTrigger(
                    minDelay = settings.adaptive.minInterval,
                    maxDelay = settings.adaptive.maxInterval,
                    batchSize = settings.batchSize,
                    clock = clock,
                )
            }

            else -> {
                error("Unsupported polling-trigger: $name")
            }
        }
    }

    /** Creates a trigger from Spring-bound properties while preserving deprecated fallbacks. */
    fun create(
        properties: OutboxProperties,
        clock: Clock,
    ): OutboxPollingTrigger = create(properties.toRuntimeSettings().polling, clock)
}
