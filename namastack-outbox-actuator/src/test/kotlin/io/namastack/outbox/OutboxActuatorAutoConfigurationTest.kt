package io.namastack.outbox

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@DisplayName("OutboxActuatorAutoConfiguration")
class OutboxActuatorAutoConfigurationTest {
    private fun contextRunner() =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxActuatorAutoConfiguration::class.java))

    @Nested
    @DisplayName("Actuator Endpoint")
    inner class ActuatorEndpoint {
        @Test
        fun `creates outbox endpoint when repository exists`() {
            contextRunner()
                .withUserConfiguration(ConfigWithRepository::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(OutboxActuatorEndpoint::class.java)
                }
        }

        @Test
        fun `throws when repository is missing`() {
            contextRunner()
                .withUserConfiguration(EmptyConfig::class.java)
                .run { context ->
                    assertThat(context.startupFailure?.message).contains("OutboxRecordRepository")
                }
        }
    }

    @Configuration
    private class EmptyConfig

    @Configuration
    private class ConfigWithRepository {
        @Bean
        fun outboxRecordRepository() = mockk<OutboxRecordRepository>(relaxed = true)
    }
}
