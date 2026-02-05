package io.namastack.outbox.trigger

import io.namastack.outbox.OutboxProperties
import java.time.Clock
import java.time.Duration

/**
 * Factory for creating [OutboxPollingTrigger] instances based on configuration properties.
 *
 * This factory creates the appropriate trigger implementation based on the configured
 * polling strategy. It supports:
 * - "fixed": Creates a [FixedPollingTrigger] with constant delay
 * - "adaptive": Creates an [AdaptivePollingTrigger] with dynamic delay adjustment
 *
 * The factory handles backward compatibility with deprecated properties by falling back
 * to legacy configuration values when new properties are not set.
 *
 * @author Aleksander Zamojski
 * @since 1.1.0
 */
internal object OutboxPollingTriggerFactory {
    /**
     * Creates an appropriate [OutboxPollingTrigger] based on the provided properties.
     *
     * The trigger type is determined by [OutboxProperties.Polling.trigger]. Supported values:
     * - "fixed": Creates a fixed delay trigger
     * - "adaptive": Creates an adaptive delay trigger
     *
     * For backward compatibility, deprecated properties ([OutboxProperties.pollInterval]
     * and [OutboxProperties.batchSize]) are used as fallbacks when new properties are not set.
     *
     * @param properties The outbox configuration properties
     * @param clock The clock to use for time calculations
     * @return The configured polling trigger
     * @throws IllegalStateException if an unsupported trigger type is specified
     */
    fun create(
        properties: OutboxProperties,
        clock: Clock,
    ): OutboxPollingTrigger {
        val name = properties.polling.trigger

        return when (name.lowercase()) {
            "fixed" -> {
                FixedPollingTrigger(
                    delay = Duration.ofMillis(properties.pollInterval ?: properties.polling.fixed.interval),
                    clock = clock,
                )
            }

            "adaptive" -> {
                AdaptivePollingTrigger(
                    minDelay = Duration.ofMillis(properties.polling.adaptive.minInterval),
                    maxDelay = Duration.ofMillis(properties.polling.adaptive.maxInterval),
                    batchSize = properties.batchSize ?: properties.polling.batchSize,
                    clock = clock,
                )
            }

            else -> {
                error("Unsupported polling-trigger: $name")
            }
        }
    }
}
