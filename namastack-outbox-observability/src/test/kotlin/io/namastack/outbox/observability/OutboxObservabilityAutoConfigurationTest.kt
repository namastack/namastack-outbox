package io.namastack.outbox.observability

import io.micrometer.observation.ObservationRegistry
import io.namastack.outbox.instrumentation.OutboxInstrumentation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@DisplayName("OutboxObservabilityAutoConfiguration")
class OutboxObservabilityAutoConfigurationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxObservabilityAutoConfiguration::class.java))
            .withUserConfiguration(ObservationRegistryConfig::class.java)

    @Nested
    @DisplayName("Bean Creation")
    inner class BeanCreation {
        @Test
        fun `creates Micrometer instrumentation without AOP advisors`() {
            contextRunner.run { context ->
                assertThat(context).hasSingleBean(MicrometerOutboxInstrumentation::class.java)
                assertThat(context.getBeansOfType(OutboxInstrumentation::class.java)).hasSize(1)
                assertThat(context).doesNotHaveBean("outboxObservabilityHandlerAdvisor")
                assertThat(context).doesNotHaveBean("outboxObservabilityFallbackAdvisor")
                assertThat(context).doesNotHaveBean("outboxObservabilityScheduleAdvisor")
            }
        }

        @Test
        fun `custom instrumentation does not disable Micrometer instrumentation`() {
            contextRunner
                .withUserConfiguration(CustomInstrumentationConfig::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(MicrometerOutboxInstrumentation::class.java)
                    assertThat(context.getBeansOfType(OutboxInstrumentation::class.java)).hasSize(2)
                }
        }

        @Test
        fun `does not create Micrometer instrumentation without observation registry`() {
            ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OutboxObservabilityAutoConfiguration::class.java))
                .run { context ->
                    assertThat(context).doesNotHaveBean(MicrometerOutboxInstrumentation::class.java)
                }
        }

        @Test
        fun `creates Micrometer instrumentation when Boot provides the observation registry`() {
            ApplicationContextRunner()
                .withConfiguration(
                    AutoConfigurations.of(
                        OutboxObservabilityAutoConfiguration::class.java,
                        ObservationAutoConfiguration::class.java,
                    ),
                ).run { context ->
                    assertThat(context).hasSingleBean(ObservationRegistry::class.java)
                    assertThat(context).hasSingleBean(MicrometerOutboxInstrumentation::class.java)
                }
        }
    }

    @Nested
    @DisplayName("Conditional Properties")
    inner class ConditionalProperties {
        @Test
        fun `does not create observability beans when outbox is disabled`() {
            contextRunner
                .withPropertyValues("namastack.outbox.enabled=false")
                .run { context ->
                    assertThat(context).doesNotHaveBean(MicrometerOutboxInstrumentation::class.java)
                }
        }

        @Test
        fun `instrumentation remains available in channels mode`() {
            contextRunner
                .withPropertyValues("namastack.outbox.mode=channels")
                .run { context ->
                    assertThat(context).hasNotFailed()
                    assertThat(context).hasSingleBean(MicrometerOutboxInstrumentation::class.java)
                }
        }
    }

    @Configuration
    class ObservationRegistryConfig {
        @Bean
        fun observationRegistry(): ObservationRegistry = ObservationRegistry.create()
    }

    @Configuration
    class CustomInstrumentationConfig {
        @Bean
        fun customInstrumentation(): OutboxInstrumentation = OutboxInstrumentation.NOOP
    }
}
