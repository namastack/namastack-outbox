package io.namastack.outbox.observability.metrics

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Controls background collection of database-backed outbox gauges.
 *
 * @property enabled Whether to register state gauges and collect their values
 * @property refreshInterval Delay after a collection finishes before the next one starts; at least 1ms
 * @property maxAge Maximum age of a reading before it reports NaN; at least [refreshInterval]
 *
 * @author Roland Beisel
 * @since 1.12.0
 */
@ConfigurationProperties("namastack.outbox.observability.metrics")
internal data class OutboxMetricsProperties(
    val enabled: Boolean = true,
    val refreshInterval: Duration = Duration.ofSeconds(15),
    val maxAge: Duration = Duration.ofSeconds(60),
) {
    init {
        require(refreshInterval >= Duration.ofMillis(1)) { "Metrics refresh-interval must be at least 1ms" }
        require(maxAge >= refreshInterval) { "Metrics max-age must be at least refresh-interval" }
    }
}
