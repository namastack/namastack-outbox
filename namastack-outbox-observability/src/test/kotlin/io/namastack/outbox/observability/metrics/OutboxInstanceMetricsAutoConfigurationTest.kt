package io.namastack.outbox.observability.metrics

import io.mockk.mockk
import io.namastack.outbox.OutboxChannelNameProvider
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxRecordStatusRepository
import io.namastack.outbox.instance.OutboxInstanceRegistry
import io.namastack.outbox.partition.PartitionCoordinator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@DisplayName("OutboxInstanceMetricsAutoConfiguration")
class OutboxInstanceMetricsAutoConfigurationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxInstanceMetricsAutoConfiguration::class.java))
            .withUserConfiguration(ChannelProviderConfig::class.java)

    @Nested
    @DisplayName("Bean Creation")
    inner class BeanCreation {
        @Test
        fun `creates meter binder when required beans exist`() {
            contextRunner
                .withUserConfiguration(RequiredBeansConfig::class.java, StatusRepositoryConfig::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(OutboxInstanceMetricsMeterBinder::class.java)
                }
        }

        @Test
        fun `creates meter binder when status repository is missing`() {
            contextRunner
                .withUserConfiguration(RequiredBeansConfig::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(OutboxInstanceMetricsMeterBinder::class.java)
                }
        }

        @Test
        fun `backs off when custom meter binder exists`() {
            contextRunner
                .withUserConfiguration(RequiredBeansConfig::class.java, CustomMeterBinderConfig::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(OutboxInstanceMetricsMeterBinder::class.java)
                    assertThat(context).hasBean("customMeterBinder")
                    assertThat(context).doesNotHaveBean("outboxInstanceMetricsMeterBinder")
                }
        }
    }

    @Nested
    @DisplayName("Required Dependencies")
    inner class RequiredDependencies {
        @Test
        fun `does not create meter binder when record repository is missing`() {
            contextRunner
                .withUserConfiguration(MissingRecordRepositoryConfig::class.java)
                .run { context ->
                    assertThat(context).doesNotHaveBean(OutboxInstanceMetricsMeterBinder::class.java)
                }
        }

        @Test
        fun `does not create meter binder when partition coordinator is missing`() {
            contextRunner
                .withUserConfiguration(MissingPartitionCoordinatorConfig::class.java)
                .run { context ->
                    assertThat(context).doesNotHaveBean(OutboxInstanceMetricsMeterBinder::class.java)
                }
        }

        @Test
        fun `does not create meter binder when instance registry is missing`() {
            contextRunner
                .withUserConfiguration(MissingInstanceRegistryConfig::class.java)
                .run { context ->
                    assertThat(context).doesNotHaveBean(OutboxInstanceMetricsMeterBinder::class.java)
                }
        }
    }

    @Nested
    @DisplayName("Conditional Properties")
    inner class ConditionalProperties {
        @Test
        fun `does not create meter binder when outbox is disabled`() {
            contextRunner
                .withUserConfiguration(RequiredBeansConfig::class.java)
                .withPropertyValues("namastack.outbox.enabled=false")
                .run { context ->
                    assertThat(context).doesNotHaveBean(OutboxInstanceMetricsMeterBinder::class.java)
                }
        }
    }

    @Configuration
    class ChannelProviderConfig {
        @Bean
        fun outboxChannelNameProvider(): OutboxChannelNameProvider = OutboxChannelNameProvider.DEFAULT
    }

    @Configuration
    class RequiredBeansConfig {
        @Bean
        fun outboxRecordRepository(): OutboxRecordRepository = mockk()

        @Bean
        fun partitionCoordinator(): PartitionCoordinator = mockk()

        @Bean
        fun outboxInstanceRegistry(): OutboxInstanceRegistry = mockk(relaxed = true)
    }

    @Configuration
    class StatusRepositoryConfig {
        @Bean
        fun outboxRecordStatusRepository(): OutboxRecordStatusRepository = mockk()
    }

    @Configuration
    class CustomMeterBinderConfig {
        @Bean
        fun customMeterBinder(): OutboxInstanceMetricsMeterBinder = mockk()
    }

    @Configuration
    class MissingRecordRepositoryConfig {
        @Bean
        fun partitionCoordinator(): PartitionCoordinator = mockk()

        @Bean
        fun outboxInstanceRegistry(): OutboxInstanceRegistry = mockk(relaxed = true)
    }

    @Configuration
    class MissingPartitionCoordinatorConfig {
        @Bean
        fun outboxRecordRepository(): OutboxRecordRepository = mockk()

        @Bean
        fun outboxInstanceRegistry(): OutboxInstanceRegistry = mockk(relaxed = true)
    }

    @Configuration
    class MissingInstanceRegistryConfig {
        @Bean
        fun outboxRecordRepository(): OutboxRecordRepository = mockk()

        @Bean
        fun partitionCoordinator(): PartitionCoordinator = mockk()
    }
}
