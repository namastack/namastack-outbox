package io.namastack.outbox

import io.mockk.mockk
import io.namastack.outbox.instance.OutboxInstanceRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.BeanCreationException
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@DisplayName("OutboxMetricsAutoConfiguration")
class OutboxMetricsAutoConfigurationTest {
    private fun contextRunner() =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxMetricsAutoConfiguration::class.java))

    @Nested
    @DisplayName("Bean Creation")
    inner class BeanCreation {
        @Test
        fun `creates all metrics beans when dependencies exist`() {
            contextRunner()
                .withUserConfiguration(ConfigWithAllBeans::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(OutboxRecordMetricsMeterBinder::class.java)
                    assertThat(context).hasSingleBean(OutboxPartitionMetricsProvider::class.java)
                    assertThat(context).hasSingleBean(OutboxPartitionMetricsMeterBinder::class.java)
                }
        }

        @Test
        fun `throws when OutboxRecordStatusRepository is missing`() {
            contextRunner()
                .withUserConfiguration(ConfigMissingStatusRepo::class.java)
                .run { context ->
                    assertThat(context.startupFailure)
                        .isInstanceOf(BeanCreationException::class.java)
                    assertThat(context.startupFailure!!)
                        .hasMessageContaining("OutboxRecordStatusRepository bean is missing")
                }
        }

        @Test
        fun `throws when OutboxRecordRepository is missing`() {
            contextRunner()
                .withUserConfiguration(ConfigMissingRecordRepo::class.java)
                .run { context ->
                    assertThat(context.startupFailure)
                        .isInstanceOf(BeanCreationException::class.java)
                    assertThat(context.startupFailure!!)
                        .hasMessageContaining("OutboxRecordRepository bean is missing")
                }
        }

        @Test
        fun `throws when PartitionCoordinator is missing`() {
            contextRunner()
                .withUserConfiguration(ConfigMissingCoordinator::class.java)
                .run { context ->
                    assertThat(context.startupFailure)
                        .isInstanceOf(BeanCreationException::class.java)
                    assertThat(context.startupFailure!!)
                        .hasMessageContaining("PartitionCoordinator bean is missing")
                }
        }

        @Test
        fun `throws when OutboxInstanceRegistry is missing`() {
            contextRunner()
                .withUserConfiguration(ConfigMissingRegistry::class.java)
                .run { context ->
                    assertThat(context.startupFailure)
                        .isInstanceOf(BeanCreationException::class.java)
                    assertThat(context.startupFailure!!)
                        .hasMessageContaining("OutboxInstanceRegistry bean is missing")
                }
        }
    }

    @EnableOutbox
    @Configuration
    class ConfigWithAllBeans {
        @Bean
        fun outboxRecordStatusRepository() = mockk<OutboxRecordStatusRepository>()

        @Bean
        fun outboxRecordRepository() = mockk<OutboxRecordRepository>()

        @Bean
        fun partitionCoordinator() = mockk<io.namastack.outbox.partition.PartitionCoordinator>()

        @Bean
        fun outboxInstanceRegistry(): OutboxInstanceRegistry {
            val mock = mockk<OutboxInstanceRegistry>(relaxed = true)
            io.mockk.every { mock.registerInstance() } returns Unit
            io.mockk.every { mock.getCurrentInstanceId() } returns "test-instance"
            return mock
        }
    }

    @EnableOutbox
    @Configuration
    class ConfigMissingStatusRepo {
        @Bean
        fun outboxRecordRepository() = mockk<OutboxRecordRepository>()

        @Bean
        fun partitionCoordinator() = mockk<io.namastack.outbox.partition.PartitionCoordinator>()

        @Bean
        fun outboxInstanceRegistry(): OutboxInstanceRegistry {
            val mock = mockk<OutboxInstanceRegistry>(relaxed = true)
            io.mockk.every { mock.registerInstance() } returns Unit
            io.mockk.every { mock.getCurrentInstanceId() } returns "test-instance"
            return mock
        }
    }

    @EnableOutbox
    @Configuration
    class ConfigMissingRecordRepo {
        @Bean
        fun outboxRecordStatusRepository() = mockk<OutboxRecordStatusRepository>()

        @Bean
        fun partitionCoordinator() = mockk<io.namastack.outbox.partition.PartitionCoordinator>()

        @Bean
        fun outboxInstanceRegistry(): OutboxInstanceRegistry {
            val mock = mockk<OutboxInstanceRegistry>(relaxed = true)
            io.mockk.every { mock.registerInstance() } returns Unit
            io.mockk.every { mock.getCurrentInstanceId() } returns "test-instance"
            return mock
        }
    }

    @EnableOutbox
    @Configuration
    class ConfigMissingCoordinator {
        @Bean
        fun outboxRecordStatusRepository() = mockk<OutboxRecordStatusRepository>()

        @Bean
        fun outboxRecordRepository() = mockk<OutboxRecordRepository>()

        @Bean
        fun outboxInstanceRegistry(): OutboxInstanceRegistry {
            val mock = mockk<OutboxInstanceRegistry>(relaxed = true)
            io.mockk.every { mock.registerInstance() } returns Unit
            io.mockk.every { mock.getCurrentInstanceId() } returns "test-instance"
            return mock
        }
    }

    @EnableOutbox
    @Configuration
    class ConfigMissingRegistry {
        @Bean
        fun outboxRecordStatusRepository() = mockk<OutboxRecordStatusRepository>()

        @Bean
        fun outboxRecordRepository() = mockk<OutboxRecordRepository>()

        @Bean
        fun partitionCoordinator() = mockk<io.namastack.outbox.partition.PartitionCoordinator>()
    }

    @EnableOutbox
    @Configuration
    class ConfigMissingMetricsProvider {
        @Bean
        fun outboxRecordStatusRepository() = mockk<OutboxRecordStatusRepository>()

        @Bean
        fun outboxRecordRepository() = mockk<OutboxRecordRepository>()

        @Bean
        fun partitionCoordinator() = mockk<io.namastack.outbox.partition.PartitionCoordinator>()

        @Bean
        fun outboxInstanceRegistry(): OutboxInstanceRegistry {
            val mock = mockk<OutboxInstanceRegistry>(relaxed = true)
            io.mockk.every { mock.registerInstance() } returns Unit
            io.mockk.every { mock.getCurrentInstanceId() } returns "test-instance"
            return mock
        }
    }
}
