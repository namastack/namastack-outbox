package io.namastack.outbox.kafka

import io.mockk.mockk
import io.namastack.outbox.handler.OutboxRecordMetadata
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaOperations
import java.time.Instant

@DisplayName("KafkaOutboxAutoConfiguration")
class KafkaOutboxAutoConfigurationTest {
    private fun contextRunner() =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaOutboxAutoConfiguration::class.java))

    @Nested
    @DisplayName("KafkaOutboxRouting Bean")
    inner class KafkaOutboxRoutingBean {
        @Test
        fun `creates default KafkaOutboxRouting bean`() {
            contextRunner()
                .withUserConfiguration(ConfigWithKafkaOperations::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(KafkaOutboxRouting::class.java)
                }
        }

        @Test
        fun `does not create routing configuration when one is already provided`() {
            contextRunner()
                .withUserConfiguration(ConfigWithKafkaOperations::class.java, ConfigWithCustomRouting::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(KafkaOutboxRouting::class.java)
                    val config = context.getBean<KafkaOutboxRouting>()
                    assertThat(config.resolveTopic("payload", createMetadata())).isEqualTo("custom-topic")
                }
        }

        @Test
        fun `uses default topic from properties`() {
            contextRunner()
                .withUserConfiguration(ConfigWithKafkaOperations::class.java)
                .withPropertyValues("namastack.outbox.kafka.default-topic=my-custom-topic")
                .run { context ->
                    val config = context.getBean<KafkaOutboxRouting>()
                    assertThat(config.resolveTopic("payload", createMetadata())).isEqualTo("my-custom-topic")
                }
        }

        @Test
        fun `uses outbox-events as default topic when not configured`() {
            contextRunner()
                .withUserConfiguration(ConfigWithKafkaOperations::class.java)
                .run { context ->
                    val config = context.getBean<KafkaOutboxRouting>()
                    assertThat(config.resolveTopic("payload", createMetadata())).isEqualTo("outbox-events")
                }
        }
    }

    @Nested
    @DisplayName("KafkaOutboxHandler Bean")
    inner class KafkaOutboxHandlerBean {
        @Test
        fun `creates KafkaOutboxHandler bean when KafkaOperations is available`() {
            contextRunner()
                .withUserConfiguration(ConfigWithKafkaOperations::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(KafkaOutboxHandler::class.java)
                }
        }

        @Test
        fun `throws exception when KafkaOperations bean is missing`() {
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
    @DisplayName("Configuration Conditions")
    inner class ConfigurationConditions {
        @Test
        fun `configuration is active by default`() {
            contextRunner()
                .withUserConfiguration(ConfigWithKafkaOperations::class.java)
                .run { context ->
                    assertThat(context).hasNotFailed()
                    assertThat(context).hasSingleBean(KafkaOutboxRouting::class.java)
                    assertThat(context).hasSingleBean(KafkaOutboxHandler::class.java)
                }
        }

        @Test
        fun `configuration is disabled when property is false`() {
            contextRunner()
                .withUserConfiguration(ConfigWithKafkaOperations::class.java)
                .withPropertyValues("namastack.outbox.kafka.enabled=false")
                .run { context ->
                    assertThat(context).doesNotHaveBean(KafkaOutboxRouting::class.java)
                    assertThat(context).doesNotHaveBean(KafkaOutboxHandler::class.java)
                }
        }

        @Test
        fun `configuration is enabled when property is true`() {
            contextRunner()
                .withUserConfiguration(ConfigWithKafkaOperations::class.java)
                .withPropertyValues("namastack.outbox.kafka.enabled=true")
                .run { context ->
                    assertThat(context).hasSingleBean(KafkaOutboxRouting::class.java)
                    assertThat(context).hasSingleBean(KafkaOutboxHandler::class.java)
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
    class ConfigWithKafkaOperations {
        @Bean
        @Suppress("UNCHECKED_CAST")
        fun kafkaOperations(): KafkaOperations<Any, Any> = mockk(relaxed = true)
    }

    @Configuration
    class ConfigWithCustomRouting {
        @Bean
        fun kafkaOutboxRouting(): KafkaOutboxRouting =
            kafkaRouting {
                defaults {
                    topic("custom-topic")
                }
            }
    }

    @Configuration
    class ConfigWithCustomHandler {
        val customHandler = KafkaOutboxHandler(mockk(relaxed = true), kafkaRouting { defaults { topic("test") } })

        @Bean
        fun kafkaOutboxHandler(): KafkaOutboxHandler = customHandler
    }
}
