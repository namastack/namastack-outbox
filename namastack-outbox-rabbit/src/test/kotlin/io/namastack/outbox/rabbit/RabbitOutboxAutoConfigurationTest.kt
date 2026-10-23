package io.namastack.outbox.rabbit

import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.routing.OutboxHeaders
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitOperations
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.time.Instant

@DisplayName("RabbitOutboxAutoConfiguration")
class RabbitOutboxAutoConfigurationTest {
    private fun contextRunner() =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RabbitOutboxAutoConfiguration::class.java))

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `binds idempotency setting for custom routing`(enabled: Boolean) {
        contextRunner()
            .withUserConfiguration(ConfigWithCustomPublisher::class.java, ConfigWithCustomRouting::class.java)
            .withPropertyValues("namastack.outbox.rabbit.idempotency-header-enabled=$enabled")
            .run { context ->
                assertThat(context).hasNotFailed()
                val metadata = createMetadata().copy(recordId = "record-id")
                val publisher = context.getBean(RabbitOutboxPublisher::class.java)
                val messageSlot = slot<RabbitOutboxMessage>()

                context.getBean(RabbitOutboxHandler::class.java).handle("payload", metadata)

                verify { publisher.publish(capture(messageSlot)) }
                assertThat(messageSlot.captured.exchange).isEqualTo("custom-exchange")
                assertThat(messageSlot.captured.headers[OutboxHeaders.IDEMPOTENCY_KEY])
                    .isEqualTo(if (enabled) metadata.recordId else null)
            }
    }

    @Nested
    @DisplayName("RabbitOutboxRouting bean")
    inner class RabbitOutboxRoutingBean {
        @Test
        fun `creates default RabbitOutboxRouting bean`() {
            contextRunner()
                .withUserConfiguration(ConfigWithValidRabbitTemplate::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(RabbitOutboxRouting::class.java)
                }
        }

        @Test
        fun `does not create routing configuration when one is already provided`() {
            contextRunner()
                .withUserConfiguration(
                    ConfigWithValidRabbitTemplate::class.java,
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
                .withUserConfiguration(ConfigWithValidRabbitTemplate::class.java)
                .withPropertyValues("namastack.outbox.rabbit.default-exchange=my-custom-exchange")
                .run { context ->
                    val config = context.getBean(RabbitOutboxRouting::class.java)
                    assertThat(config.resolveExchange("payload", createMetadata())).isEqualTo("my-custom-exchange")
                }
        }

        @Test
        fun `uses outbox-events as default exchange when not configured`() {
            contextRunner()
                .withUserConfiguration(ConfigWithValidRabbitTemplate::class.java)
                .run { context ->
                    val config = context.getBean(RabbitOutboxRouting::class.java)
                    assertThat(config.resolveExchange("payload", createMetadata())).isEqualTo("outbox-events")
                }
        }
    }

    @Nested
    @DisplayName("RabbitOutboxPublisher bean")
    inner class RabbitOutboxPublisherBean {
        @Test
        fun `creates RabbitOutboxPublisher bean when RabbitOperations is available`() {
            contextRunner()
                .withUserConfiguration(ConfigWithValidRabbitTemplate::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(RabbitOutboxPublisher::class.java)
                }
        }

        @Test
        fun `fails when publisher confirms are not correlated`() {
            contextRunner()
                .withUserConfiguration(ConfigWithoutPublisherConfirms::class.java)
                .run { context ->
                    assertThat(context).hasFailed()
                    assertThat(context.startupFailure)
                        .hasRootCauseInstanceOf(IllegalStateException::class.java)
                        .hasMessageContaining("spring.rabbitmq.publisher-confirm-type=correlated")
                }
        }

        @Test
        fun `validates publisher confirms on the connection factory used by RabbitOperations`() {
            contextRunner()
                .withUserConfiguration(ConfigWithValidPrimaryAndInvalidOperationsFactory::class.java)
                .run { context ->
                    assertThat(context).hasFailed()
                    assertThat(context.startupFailure)
                        .hasRootCauseInstanceOf(IllegalStateException::class.java)
                        .hasMessageContaining("spring.rabbitmq.publisher-confirm-type=correlated")
                }
        }

        @Test
        fun `ignores unrelated primary connection factory when validating publisher confirms`() {
            contextRunner()
                .withUserConfiguration(ConfigWithInvalidPrimaryAndValidOperationsFactory::class.java)
                .run { context ->
                    assertThat(context).hasNotFailed()
                    assertThat(context).hasSingleBean(RabbitOutboxPublisher::class.java)
                }
        }

        @Test
        fun `does not fail when publisher returns are disabled by default`() {
            contextRunner()
                .withUserConfiguration(ConfigWithoutPublisherReturns::class.java)
                .run { context ->
                    assertThat(context).hasNotFailed()
                    assertThat(context).hasSingleBean(RabbitOutboxPublisher::class.java)
                }
        }

        @Test
        fun `does not fail when mandatory publishing is disabled by default`() {
            contextRunner()
                .withUserConfiguration(ConfigWithoutMandatoryTemplate::class.java)
                .run { context ->
                    assertThat(context).hasNotFailed()
                    assertThat(context).hasSingleBean(RabbitOutboxPublisher::class.java)
                }
        }

        @Test
        fun `creates publisher when fail on unroutable is enabled and settings are valid`() {
            contextRunner()
                .withUserConfiguration(ConfigWithValidRabbitTemplate::class.java)
                .withPropertyValues("namastack.outbox.rabbit.fail-on-unroutable=true")
                .run { context ->
                    assertThat(context).hasNotFailed()
                    assertThat(context).hasSingleBean(RabbitOutboxPublisher::class.java)
                }
        }

        @Test
        fun `fails when fail on unroutable is enabled and publisher returns are disabled`() {
            contextRunner()
                .withUserConfiguration(ConfigWithoutPublisherReturns::class.java)
                .withPropertyValues("namastack.outbox.rabbit.fail-on-unroutable=true")
                .run { context ->
                    assertThat(context).hasFailed()
                    assertThat(context.startupFailure)
                        .hasRootCauseInstanceOf(IllegalStateException::class.java)
                        .hasMessageContaining("spring.rabbitmq.publisher-returns=true")
                }
        }

        @Test
        fun `validates publisher returns on the connection factory used by RabbitOperations`() {
            contextRunner()
                .withUserConfiguration(ConfigWithValidPrimaryAndOperationsFactoryWithoutReturns::class.java)
                .withPropertyValues("namastack.outbox.rabbit.fail-on-unroutable=true")
                .run { context ->
                    assertThat(context).hasFailed()
                    assertThat(context.startupFailure)
                        .hasRootCauseInstanceOf(IllegalStateException::class.java)
                        .hasMessageContaining("spring.rabbitmq.publisher-returns=true")
                }
        }

        @Test
        fun `fails when fail on unroutable is enabled and mandatory publishing is disabled`() {
            contextRunner()
                .withUserConfiguration(ConfigWithoutMandatoryTemplate::class.java)
                .withPropertyValues("namastack.outbox.rabbit.fail-on-unroutable=true")
                .run { context ->
                    assertThat(context).hasFailed()
                    assertThat(context.startupFailure)
                        .hasRootCauseInstanceOf(IllegalStateException::class.java)
                        .hasMessageContaining("spring.rabbitmq.template.mandatory=true")
                }
        }

        @Test
        fun `does not create publisher when one is already provided`() {
            contextRunner()
                .withUserConfiguration(
                    ConfigWithValidRabbitTemplate::class.java,
                    ConfigWithCustomPublisher::class.java,
                ).run { context ->
                    assertThat(context).hasSingleBean(RabbitOutboxPublisher::class.java)
                    assertThat(context.getBean(RabbitOutboxPublisher::class.java))
                        .isSameAs(context.getBean("customPublisher"))
                }
        }
    }

    @Nested
    @DisplayName("RabbitOutboxHandler bean")
    inner class RabbitOutboxHandlerBean {
        @Test
        fun `creates RabbitOutboxHandler bean when RabbitOperations is available`() {
            contextRunner()
                .withUserConfiguration(ConfigWithValidRabbitTemplate::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(RabbitOutboxHandler::class.java)
                }
        }

        @Test
        fun `throws exception when RabbitOperations bean is missing`() {
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
                .withUserConfiguration(ConfigWithValidRabbitTemplate::class.java)
                .run { context ->
                    assertThat(context).hasNotFailed()
                    assertThat(context).hasSingleBean(RabbitOutboxRouting::class.java)
                    assertThat(context).hasSingleBean(RabbitOutboxPublisher::class.java)
                    assertThat(context).hasSingleBean(RabbitOutboxHandler::class.java)
                }
        }

        @Test
        fun `configuration is disabled when property is false`() {
            contextRunner()
                .withUserConfiguration(ConfigWithValidRabbitTemplate::class.java)
                .withPropertyValues("namastack.outbox.rabbit.enabled=false")
                .run { context ->
                    assertThat(context).doesNotHaveBean(RabbitOutboxRouting::class.java)
                    assertThat(context).doesNotHaveBean(RabbitOutboxPublisher::class.java)
                    assertThat(context).doesNotHaveBean(RabbitOutboxHandler::class.java)
                }
        }

        @Test
        fun `configuration is enabled when property is true`() {
            contextRunner()
                .withUserConfiguration(ConfigWithValidRabbitTemplate::class.java)
                .withPropertyValues("namastack.outbox.rabbit.enabled=true")
                .run { context ->
                    assertThat(context).hasSingleBean(RabbitOutboxRouting::class.java)
                    assertThat(context).hasSingleBean(RabbitOutboxPublisher::class.java)
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
    class ConfigWithValidRabbitTemplate {
        @Bean
        fun rabbitConnectionFactory(): ConnectionFactory = newRabbitConnectionFactory()

        @Bean
        fun rabbitOperations(connectionFactory: ConnectionFactory): RabbitOperations =
            RabbitTemplate(connectionFactory).apply {
                setMandatory(true)
            }
    }

    @Configuration
    class ConfigWithoutPublisherConfirms {
        @Bean
        fun rabbitConnectionFactory(): ConnectionFactory = newRabbitConnectionFactory(publisherConfirmType = null)

        @Bean
        fun rabbitOperations(connectionFactory: ConnectionFactory): RabbitOperations =
            mandatoryRabbitTemplate(connectionFactory)
    }

    @Configuration
    class ConfigWithoutPublisherReturns {
        @Bean
        fun rabbitConnectionFactory(): ConnectionFactory = newRabbitConnectionFactory(publisherReturns = false)

        @Bean
        fun rabbitOperations(connectionFactory: ConnectionFactory): RabbitOperations =
            mandatoryRabbitTemplate(connectionFactory)
    }

    @Configuration
    class ConfigWithoutMandatoryTemplate {
        @Bean
        fun rabbitConnectionFactory(): ConnectionFactory = newRabbitConnectionFactory()

        @Bean
        fun rabbitOperations(connectionFactory: ConnectionFactory): RabbitOperations = RabbitTemplate(connectionFactory)
    }

    @Configuration
    class ConfigWithValidPrimaryAndInvalidOperationsFactory {
        @Bean
        @Primary
        fun primaryConnectionFactory(): ConnectionFactory = newRabbitConnectionFactory()

        @Bean
        fun outboxConnectionFactory(): ConnectionFactory = newRabbitConnectionFactory(publisherConfirmType = null)

        @Bean
        fun rabbitOperations(
            @Qualifier("outboxConnectionFactory") connectionFactory: ConnectionFactory,
        ): RabbitOperations = mandatoryRabbitTemplate(connectionFactory)
    }

    @Configuration
    class ConfigWithInvalidPrimaryAndValidOperationsFactory {
        @Bean
        @Primary
        fun primaryConnectionFactory(): ConnectionFactory = newRabbitConnectionFactory(publisherConfirmType = null)

        @Bean
        fun outboxConnectionFactory(): ConnectionFactory = newRabbitConnectionFactory()

        @Bean
        fun rabbitOperations(
            @Qualifier("outboxConnectionFactory") connectionFactory: ConnectionFactory,
        ): RabbitOperations = mandatoryRabbitTemplate(connectionFactory)
    }

    @Configuration
    class ConfigWithValidPrimaryAndOperationsFactoryWithoutReturns {
        @Bean
        @Primary
        fun primaryConnectionFactory(): ConnectionFactory = newRabbitConnectionFactory()

        @Bean
        fun outboxConnectionFactory(): ConnectionFactory = newRabbitConnectionFactory(publisherReturns = false)

        @Bean
        fun rabbitOperations(
            @Qualifier("outboxConnectionFactory") connectionFactory: ConnectionFactory,
        ): RabbitOperations = mandatoryRabbitTemplate(connectionFactory)
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
    class ConfigWithCustomPublisher {
        @Bean
        fun customPublisher(): RabbitOutboxPublisher = mockk(relaxed = true)
    }

    @Configuration
    class ConfigWithCustomHandler {
        @Bean
        fun rabbitOutboxHandler(): RabbitOutboxHandler =
            RabbitOutboxHandler(
                publisher = mockk(relaxed = true),
                routing =
                    rabbitOutboxRouting {
                        defaults { target("test") }
                    },
            )
    }

    companion object {
        private fun newRabbitConnectionFactory(
            publisherConfirmType: CachingConnectionFactory.ConfirmType? =
                CachingConnectionFactory.ConfirmType.CORRELATED,
            publisherReturns: Boolean = true,
        ): ConnectionFactory =
            CachingConnectionFactory("localhost").apply {
                if (publisherConfirmType != null) {
                    setPublisherConfirmType(publisherConfirmType)
                }
                setPublisherReturns(publisherReturns)
            }

        private fun mandatoryRabbitTemplate(connectionFactory: ConnectionFactory): RabbitTemplate =
            RabbitTemplate(connectionFactory).apply {
                setMandatory(true)
            }
    }
}
