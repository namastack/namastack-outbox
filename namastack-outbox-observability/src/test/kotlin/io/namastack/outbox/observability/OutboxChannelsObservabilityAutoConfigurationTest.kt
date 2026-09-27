package io.namastack.outbox.observability

import io.micrometer.observation.ObservationRegistry
import io.namastack.outbox.Outbox
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxRuntimeMode.CHANNELS
import io.namastack.outbox.OutboxRuntimeModeProvider
import io.namastack.outbox.instrumentation.OutboxInstrumentation
import io.namastack.outbox.observability.metrics.OutboxInstanceMetricsMeterBinder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

class OutboxChannelsObservabilityAutoConfigurationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(TestApplication::class.java)
            .withPropertyValues("namastack.outbox.mode=channels")

    @Test
    fun `keeps domain instrumentation and omits single runtime metrics`() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(MicrometerOutboxInstrumentation::class.java)
            assertThat(context).hasSingleBean(OutboxInstrumentation::class.java)
            assertThat(context).doesNotHaveBean(OutboxInstanceMetricsMeterBinder::class.java)
            assertThat(context).doesNotHaveBean(OutboxRecordRepository::class.java)
            assertThat(context).doesNotHaveBean(Outbox::class.java)
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    internal class TestApplication {
        @Bean
        fun channelsRuntimeModeProvider(): OutboxRuntimeModeProvider = OutboxRuntimeModeProvider { CHANNELS }

        @Bean
        fun observationRegistry(): ObservationRegistry = ObservationRegistry.create()
    }
}
