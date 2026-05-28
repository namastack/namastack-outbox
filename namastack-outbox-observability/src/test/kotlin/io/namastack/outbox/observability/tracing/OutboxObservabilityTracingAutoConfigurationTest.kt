package io.namastack.outbox.observability.tracing

import io.micrometer.tracing.Tracer
import io.micrometer.tracing.propagation.Propagator
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@DisplayName("OutboxObservabilityTracingAutoConfiguration")
class OutboxObservabilityTracingAutoConfigurationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxObservabilityTracingAutoConfiguration::class.java))

    @Nested
    @DisplayName("Bean Creation")
    inner class BeanCreation {
        @Test
        fun `creates tracing context provider when tracing beans exist`() {
            contextRunner
                .withUserConfiguration(TracingBeansConfig::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(OutboxObservabilityTracingContextProvider::class.java)
                }
        }

        @Test
        fun `backs off when observability tracing context provider bean exists`() {
            contextRunner
                .withUserConfiguration(TracingBeansConfig::class.java, ExistingObservabilityProviderConfig::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(OutboxObservabilityTracingContextProvider::class.java)
                    assertThat(context).hasBean("customProvider")
                    assertThat(context).doesNotHaveBean("outboxObservabilityTracingContextProvider")
                }
        }

        @Test
        fun `backs off when legacy tracing context provider bean exists`() {
            contextRunner
                .withUserConfiguration(TracingBeansConfig::class.java, ExistingLegacyProviderConfig::class.java)
                .run { context ->
                    assertThat(context).hasBean("outboxTracingContextProvider")
                    assertThat(context).doesNotHaveBean("outboxObservabilityTracingContextProvider")
                }
        }
    }

    @Nested
    @DisplayName("Required Dependencies")
    inner class RequiredDependencies {
        @Test
        fun `does not create provider when tracer is missing`() {
            contextRunner
                .withUserConfiguration(PropagatorOnlyConfig::class.java)
                .run { context ->
                    assertThat(context).doesNotHaveBean(OutboxObservabilityTracingContextProvider::class.java)
                }
        }

        @Test
        fun `does not create provider when propagator is missing`() {
            contextRunner
                .withUserConfiguration(TracerOnlyConfig::class.java)
                .run { context ->
                    assertThat(context).doesNotHaveBean(OutboxObservabilityTracingContextProvider::class.java)
                }
        }
    }

    @Configuration
    class TracingBeansConfig {
        @Bean
        fun tracer(): Tracer = mockk()

        @Bean
        fun propagator(): Propagator = mockk()
    }

    @Configuration
    class TracerOnlyConfig {
        @Bean
        fun tracer(): Tracer = mockk()
    }

    @Configuration
    class PropagatorOnlyConfig {
        @Bean
        fun propagator(): Propagator = mockk()
    }

    @Configuration
    class ExistingObservabilityProviderConfig {
        @Bean
        fun customProvider(): OutboxObservabilityTracingContextProvider = mockk()
    }

    @Configuration
    class ExistingLegacyProviderConfig {
        @Bean("outboxTracingContextProvider")
        fun outboxTracingContextProvider(): OutboxObservabilityTracingContextProvider = mockk()
    }
}
