package io.namastack.outbox.runtime

import io.namastack.outbox.OutboxProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class OutboxRuntimeSettingsMappingTest {
    @Test
    @Suppress("DEPRECATION")
    fun `normalizes deprecated properties at the binding boundary`() {
        val properties =
            OutboxProperties(
                pollInterval = Duration.ofSeconds(11),
                rebalanceInterval = Duration.ofSeconds(12),
                batchSize = 13,
                polling =
                    OutboxProperties.Polling(
                        batchSize = 100,
                        fixed = OutboxProperties.FixedPolling(Duration.ofSeconds(100)),
                    ),
                processing =
                    OutboxProperties.Processing(
                        publishAfterSave = false,
                        shutdownTimeoutSeconds = 14,
                        shutdownTimeout = Duration.ofSeconds(100),
                    ),
                instance =
                    OutboxProperties.Instance(
                        heartbeatIntervalSeconds = 15,
                        staleInstanceTimeoutSeconds = 16,
                        gracefulShutdownTimeoutSeconds = 17,
                        heartbeatInterval = Duration.ofSeconds(100),
                        staleInstanceTimeout = Duration.ofSeconds(100),
                        gracefulShutdownTimeout = Duration.ofSeconds(100),
                        rebalanceInterval = Duration.ofSeconds(100),
                    ),
                multicaster = OutboxProperties.Multicaster(publishAfterSave = true),
            )

        val settings = properties.toRuntimeSettings()

        assertThat(settings.polling.fixed.interval).isEqualTo(Duration.ofSeconds(11))
        assertThat(settings.polling.batchSize).isEqualTo(13)
        assertThat(settings.processing.shutdownTimeout).isEqualTo(Duration.ofSeconds(14))
        assertThat(settings.instance.heartbeatInterval).isEqualTo(Duration.ofSeconds(15))
        assertThat(settings.instance.staleInstanceTimeout).isEqualTo(Duration.ofSeconds(16))
        assertThat(settings.instance.gracefulShutdownTimeout).isEqualTo(Duration.ofSeconds(17))
        assertThat(settings.instance.rebalanceInterval).isEqualTo(Duration.ofSeconds(12))
        assertThat(settings.multicaster.publishAfterSave).isFalse()
    }
}
