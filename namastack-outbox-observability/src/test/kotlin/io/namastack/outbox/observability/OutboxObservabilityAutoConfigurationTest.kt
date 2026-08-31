package io.namastack.outbox.observability

import io.micrometer.observation.ObservationRegistry
import io.namastack.outbox.OutboxChannelNameProvider
import io.namastack.outbox.instrumentation.OutboxInstrumentation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.aop.Advisor
import org.springframework.boot.autoconfigure.AutoConfigurations
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
        fun `creates observation advisors and default channel provider`() {
            contextRunner.run { context ->
                assertThat(context).hasSingleBean(OutboxChannelNameProvider::class.java)
                assertThat(context).hasBean("outboxObservabilityHandlerAdvisor")
                assertThat(context).hasBean("outboxObservabilityFallbackAdvisor")
                assertThat(context).hasBean("outboxObservabilityScheduleAdvisor")
                assertThat(context.getBeansOfType(Advisor::class.java)).hasSize(3)
                assertThat(context).hasSingleBean(MicrometerOutboxInstrumentation::class.java)
                assertThat(context.getBeansOfType(OutboxInstrumentation::class.java)).hasSize(1)
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
        fun `does not create processing cycle advisor`() {
            contextRunner.run { context ->
                assertThat(context).doesNotHaveBean("outboxObservabilityProcessingCycleAdvisor")
                assertThat(context.getBeansOfType(Advisor::class.java).keys)
                    .doesNotContain("outboxObservabilityProcessingCycleAdvisor")
            }
        }

        @Test
        fun `backs off when custom channel provider exists`() {
            contextRunner
                .withUserConfiguration(CustomChannelProviderConfig::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(OutboxChannelNameProvider::class.java)
                    assertThat(context.getBean(OutboxChannelNameProvider::class.java).getChannelName())
                        .isEqualTo("custom")
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
                    assertThat(context).doesNotHaveBean("outboxObservabilityHandlerAdvisor")
                    assertThat(context).doesNotHaveBean("outboxObservabilityFallbackAdvisor")
                    assertThat(context).doesNotHaveBean("outboxObservabilityScheduleAdvisor")
                    assertThat(context).doesNotHaveBean(MicrometerOutboxInstrumentation::class.java)
                }
        }
    }

    @Configuration
    class ObservationRegistryConfig {
        @Bean
        fun observationRegistry(): ObservationRegistry = ObservationRegistry.create()
    }

    @Configuration
    class CustomChannelProviderConfig {
        @Bean
        fun outboxChannelNameProvider(): OutboxChannelNameProvider = OutboxChannelNameProvider { "custom" }
    }

    @Configuration
    class CustomInstrumentationConfig {
        @Bean
        fun customInstrumentation(): OutboxInstrumentation = OutboxInstrumentation.NOOP
    }
}
