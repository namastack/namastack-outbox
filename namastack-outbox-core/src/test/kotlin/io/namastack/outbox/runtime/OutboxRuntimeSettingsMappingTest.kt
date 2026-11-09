package io.namastack.outbox.runtime

import io.namastack.outbox.OutboxProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import java.time.Duration

class OutboxRuntimeSettingsMappingTest {
    @Test
    fun `completion batching stays disabled by default when outbox is enabled`() {
        val settings = OutboxProperties(enabled = true).toRuntimeSettings()

        assertThat(settings.completionBatching.enabled).isFalse()
    }

    @Test
    fun `captures completion batching properties in runtime settings`() {
        val properties =
            OutboxProperties(
                enabled = false,
                completionBatching =
                    OutboxProperties.CompletionBatching(
                        enabled = true,
                        writerCount = 3,
                        maxBatchSize = 128,
                        maxWait = Duration.ofMillis(5),
                    ),
            )
        val settings = properties.toRuntimeSettings()
        properties.completionBatching.writerCount = 7

        assertThat(settings.completionBatching).isEqualTo(
            OutboxRuntimeSettings.CompletionBatching(
                enabled = true,
                writerCount = 3,
                maxBatchSize = 128,
                maxWait = Duration.ofMillis(5),
            ),
        )
    }

    @Test
    fun `binds supported property names and duration units before mapping`() {
        val source =
            MapConfigurationPropertySource(
                mapOf(
                    "namastack.outbox.polling.fixed.interval" to "250ms",
                    "namastack.outbox.polling.batch-size" to "42",
                    "namastack.outbox.instance.rebalance-interval" to "1m",
                    "namastack.outbox.multicaster.publish-after-save" to "false",
                    "namastack.outbox.processing.shutdown-timeout" to "30s",
                    "namastack.outbox.instance.heartbeat-interval" to "5s",
                    "namastack.outbox.instance.stale-instance-timeout" to "2m",
                    "namastack.outbox.instance.graceful-shutdown-timeout" to "500ms",
                ),
            )
        val properties = Binder(source).bind("namastack.outbox", Bindable.of(OutboxProperties::class.java)).get()
        val settings = properties.toRuntimeSettings()

        assertThat(settings.polling.fixed.interval).isEqualTo(Duration.ofMillis(250))
        assertThat(settings.polling.batchSize).isEqualTo(42)
        assertThat(settings.instance.rebalanceInterval).isEqualTo(Duration.ofMinutes(1))
        assertThat(settings.multicaster.publishAfterSave).isFalse()
        assertThat(settings.processing.shutdownTimeout).isEqualTo(Duration.ofSeconds(30))
        assertThat(settings.instance.heartbeatInterval).isEqualTo(Duration.ofSeconds(5))
        assertThat(settings.instance.staleInstanceTimeout).isEqualTo(Duration.ofMinutes(2))
        assertThat(settings.instance.gracefulShutdownTimeout).isEqualTo(Duration.ofMillis(500))
    }

    @Test
    fun `maps supported properties to runtime settings`() {
        val properties =
            OutboxProperties(
                polling =
                    OutboxProperties.Polling(
                        batchSize = 13,
                        fixed = OutboxProperties.FixedPolling(Duration.ofSeconds(11)),
                    ),
                processing =
                    OutboxProperties.Processing(
                        shutdownTimeout = Duration.ofSeconds(14),
                    ),
                instance =
                    OutboxProperties.Instance(
                        heartbeatInterval = Duration.ofSeconds(15),
                        staleInstanceTimeout = Duration.ofSeconds(16),
                        gracefulShutdownTimeout = Duration.ofSeconds(17),
                        rebalanceInterval = Duration.ofSeconds(12),
                    ),
                multicaster = OutboxProperties.Multicaster(publishAfterSave = false),
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

    @Test
    fun `preserves runtime defaults`() {
        assertThat(OutboxProperties().toRuntimeSettings()).isEqualTo(OutboxRuntimeSettings())
    }

    @Test
    fun `maps property strategy names to runtime enums`() {
        val properties =
            OutboxProperties(
                polling = OutboxProperties.Polling(trigger = "ADAPTIVE"),
                retry = OutboxProperties.Retry(policy = "LINEAR"),
            )

        val settings = properties.toRuntimeSettings()

        assertThat(settings.polling.trigger).isEqualTo(OutboxRuntimeSettings.Polling.Trigger.ADAPTIVE)
        assertThat(settings.retry.policy).isEqualTo(OutboxRuntimeSettings.Retry.Policy.LINEAR)
    }
}
