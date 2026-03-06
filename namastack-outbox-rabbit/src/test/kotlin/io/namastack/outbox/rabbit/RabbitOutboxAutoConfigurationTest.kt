package io.namastack.outbox.rabbit

import io.mockk.mockk
import io.namastack.outbox.handler.OutboxRecordMetadata
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.amqp.rabbit.core.RabbitMessageOperations
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Instant

@DisplayName("RabbitOutboxAutoConfiguration")
class RabbitOutboxAutoConfigurationTest {
    private fun contextRunner() =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RabbitOutboxAutoConfiguration::class.java))

    @Nested
    @DisplayName("RabbitOutboxRouting bean")
    inner class RabbitOutboxRoutingBean {
        @Test
        fun `creates default RabbitOutboxRouting bean`() {
            contextRunner()
                .withUserConfiguration(ConfigWithRabbitMessageOperations::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(RabbitOutboxRouting::class.java)
                }
        }

        @Test
        fun `does not create routing configuration when one is already provided`() {
            contextRunner()
                .withUserConfiguration(
                    ConfigWithRabbitMessageOperations::class.java,
                    ConfigWithCustomRouting::class.java,
                ).run { context ->
                    assertThat(context).hasSingleBean(RabbitOutboxRouting::class.java)
                    val config = context.getBean(RabbitOutboxRouting::class.java)
                    assertThat(config.resolveExchange("payload", createMetadata())).isEqualTo("custom-exchange")
                }
        }

        @Test
        fun `uses default exchange from properties`() {
            contextRunner()
                .withUserConfiguration(ConfigWithRabbitMessageOperations::class.java)
                .withPropertyValues("namastack.outbox.rabbit.default-exchange=my-custom-exchange")
                .run { context ->
                    val config = context.getBean(RabbitOutboxRouting::class.java)
                    assertThat(config.resolveExchange("payload", createMetadata())).isEqualTo("my-custom-exchange")
                }
        }

        @Test
        fun `uses outbox-events as default exchange when not configured`() {
            contextRunner()
                .withUserConfiguration(ConfigWithRabbitMessageOperations::class.java)
                .run { context ->
                    val config = context.getBean(RabbitOutboxRouting::class.java)
                    assertThat(config.resolveExchange("payload", createMetadata())).isEqualTo("outbox-events")
                }
        }
    }

    @Nested
    @DisplayName("RabbitOutboxHandler bean")
    inner class RabbitOutboxHandlerBean {
        @Test
        fun `creates RabbitOutboxHandler bean when RabbitMessageOperations is available`() {
            contextRunner()
                .withUserConfiguration(ConfigWithRabbitMessageOperations::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(RabbitOutboxHandler::class.java)
                }
        }

        @Test
        fun `throws exception when RabbitMessageOperations bean is missing`() {
            contextRunner()
                .run { context ->
                    assertThat(context).hasFailed()
                    assertThat(context.startupFailure).hasRootCauseInstanceOf(
                        NoSuchBeanDefinitionException::class.java,
                    )
                }
        }
    }

    @Nested
    @DisplayName("configuration conditions")
    inner class ConfigurationConditions {
        @Test
        fun `configuration is active by default`() {
            contextRunner()
                .withUserConfiguration(ConfigWithRabbitMessageOperations::class.java)
                .run { context ->
                    assertThat(context).hasNotFailed()
                    assertThat(context).hasSingleBean(RabbitOutboxRouting::class.java)
                    assertThat(context).hasSingleBean(RabbitOutboxHandler::class.java)
                }
        }

        @Test
        fun `configuration is disabled when property is false`() {
            contextRunner()
                .withUserConfiguration(ConfigWithRabbitMessageOperations::class.java)
                .withPropertyValues("namastack.outbox.rabbit.enabled=false")
                .run { context ->
                    assertThat(context).doesNotHaveBean(RabbitOutboxRouting::class.java)
                    assertThat(context).doesNotHaveBean(RabbitOutboxHandler::class.java)
                }
        }

        @Test
        fun `configuration is enabled when property is true`() {
            contextRunner()
                .withUserConfiguration(ConfigWithRabbitMessageOperations::class.java)
                .withPropertyValues("namastack.outbox.rabbit.enabled=true")
                .run { context ->
                    assertThat(context).hasSingleBean(RabbitOutboxRouting::class.java)
                    assertThat(context).hasSingleBean(RabbitOutboxHandler::class.java)
                }
        }
    }

    private fun createMetadata() =
        OutboxRecordMetadata(
            key = "test-key",
            handlerId = "test-handler",
            createdAt = Instant.now(),
            context = emptyMap(),
        )

    @Configuration
    class ConfigWithRabbitMessageOperations {
        @Bean
        fun rabbitMessageOperations(): RabbitMessageOperations = mockk(relaxed = true)
    }

    @Configuration
    class ConfigWithCustomRouting {
        @Bean
        fun rabbitOutboxRouting(): RabbitOutboxRouting =
            rabbitOutboxRouting {
                defaults {
                    target("custom-exchange")
                }
            }
    }

    @Configuration
    class ConfigWithCustomHandler {
        @Bean
        fun rabbitOutboxHandler(): RabbitOutboxHandler =
            RabbitOutboxHandler(
                rabbitMessageOperations = mockk(relaxed = true),
                routing =
                    rabbitOutboxRouting {
                        defaults { target("test") }
                    },
            )
    }
}
