package io.namastack.outbox.sns

import io.awspring.cloud.sns.core.SnsNotification
import io.awspring.cloud.sns.core.SnsOperations
import io.awspring.cloud.sns.core.SnsTemplate
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
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Instant

@DisplayName("SnsOutboxAutoConfiguration")
class SnsOutboxAutoConfigurationTest {
    private fun contextRunner() =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SnsOutboxAutoConfiguration::class.java))

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `binds idempotency setting for custom routing`(enabled: Boolean) {
        contextRunner()
            .withUserConfiguration(ConfigWithSnsOperations::class.java, ConfigWithCustomRouting::class.java)
            .withPropertyValues("namastack.outbox.sns.idempotency-header-enabled=$enabled")
            .run { context ->
                assertThat(context).hasNotFailed()
                val metadata = createMetadata().copy(recordId = "record-id")
                val operations = context.getBean("snsOperations", SnsOperations::class.java)
                val notificationSlot = slot<SnsNotification<*>>()

                context.getBean(SnsOutboxHandler::class.java).handle("payload", metadata)

                verify { operations.sendNotification(any<String>(), capture(notificationSlot)) }
                assertThat(notificationSlot.captured.headers?.get(OutboxHeaders.IDEMPOTENCY_KEY))
                    .isEqualTo(if (enabled) metadata.recordId else null)
                assertThat(notificationSlot.captured.deduplicationId).isNull()
            }
    }

    @Test
    fun `binds native FIFO deduplication independently of idempotency attributes`() {
        contextRunner()
            .withUserConfiguration(ConfigWithSnsOperations::class.java)
            .withPropertyValues(
                "namastack.outbox.sns.fifo-deduplication-id-enabled=true",
                "namastack.outbox.sns.default-topic-arn=arn:aws:sns:us-east-1:123456789012:events.fifo",
            ).run { context ->
                assertThat(context).hasNotFailed()
                val metadata = createMetadata().copy(recordId = "record-id")
                val operations = context.getBean("snsOperations", SnsOperations::class.java)
                val notificationSlot = slot<SnsNotification<*>>()

                context.getBean(SnsOutboxHandler::class.java).handle("payload", metadata)

                verify { operations.sendNotification(any<String>(), capture(notificationSlot)) }
                assertThat(notificationSlot.captured.deduplicationId).isEqualTo(metadata.recordId)
                assertThat(notificationSlot.captured.headers).doesNotContainKey(OutboxHeaders.IDEMPOTENCY_KEY)
            }
    }

    @Nested
    @DisplayName("SnsOutboxRouting bean")
    inner class SnsOutboxRoutingBean {
        @Test
        fun `creates default SnsOutboxRouting bean`() {
            contextRunner()
                .withUserConfiguration(ConfigWithSnsOperations::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(SnsOutboxRouting::class.java)
                }
        }

        @Test
        fun `does not create routing configuration when one is already provided`() {
            contextRunner()
                .withUserConfiguration(
                    ConfigWithSnsOperations::class.java,
                    ConfigWithCustomRouting::class.java,
                ).run { context ->
                    assertThat(context).hasSingleBean(SnsOutboxRouting::class.java)
                    val config = context.getBean(SnsOutboxRouting::class.java)
                    assertThat(config.resolveTopicArn("payload", createMetadata()))
                        .isEqualTo("arn:aws:sns:us-east-1:123456789012:custom-topic")
                }
        }

        @Test
        fun `uses default topic ARN from properties`() {
            contextRunner()
                .withUserConfiguration(ConfigWithSnsOperations::class.java)
                .withPropertyValues(
                    "namastack.outbox.sns.default-topic-arn=arn:aws:sns:us-east-1:123456789012:my-topic",
                ).run { context ->
                    val config = context.getBean(SnsOutboxRouting::class.java)
                    assertThat(config.resolveTopicArn("payload", createMetadata()))
                        .isEqualTo("arn:aws:sns:us-east-1:123456789012:my-topic")
                }
        }

        @Test
        fun `uses outbox-events ARN as default when not configured`() {
            contextRunner()
                .withUserConfiguration(ConfigWithSnsOperations::class.java)
                .run { context ->
                    val config = context.getBean(SnsOutboxRouting::class.java)
                    assertThat(config.resolveTopicArn("payload", createMetadata()))
                        .isEqualTo("arn:aws:sns:us-east-1:000000000000:outbox-events")
                }
        }
    }

    @Nested
    @DisplayName("SnsOutboxHandler bean")
    inner class SnsOutboxHandlerBean {
        @Test
        fun `creates SnsOutboxHandler bean when SnsOperations is available`() {
            contextRunner()
                .withUserConfiguration(ConfigWithSnsOperations::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(SnsOutboxHandler::class.java)
                }
        }

        @Test
        fun `throws exception when SnsOperations bean is missing`() {
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
                .withUserConfiguration(ConfigWithSnsOperations::class.java)
                .run { context ->
                    assertThat(context).hasNotFailed()
                    assertThat(context).hasSingleBean(SnsOutboxRouting::class.java)
                    assertThat(context).hasSingleBean(SnsOutboxHandler::class.java)
                }
        }

        @Test
        fun `configuration is disabled when property is false`() {
            contextRunner()
                .withUserConfiguration(ConfigWithSnsOperations::class.java)
                .withPropertyValues("namastack.outbox.sns.enabled=false")
                .run { context ->
                    assertThat(context).doesNotHaveBean(SnsOutboxRouting::class.java)
                    assertThat(context).doesNotHaveBean(SnsOutboxHandler::class.java)
                }
        }

        @Test
        fun `configuration is enabled when property is true`() {
            contextRunner()
                .withUserConfiguration(ConfigWithSnsOperations::class.java)
                .withPropertyValues("namastack.outbox.sns.enabled=true")
                .run { context ->
                    assertThat(context).hasSingleBean(SnsOutboxRouting::class.java)
                    assertThat(context).hasSingleBean(SnsOutboxHandler::class.java)
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
    class ConfigWithSnsOperations {
        @Bean
        fun snsOperations(): SnsOperations = mockk(relaxed = true)

        @Bean
        fun snsTemplate(): SnsTemplate = mockk(relaxed = true)
    }

    @Configuration
    class ConfigWithCustomRouting {
        @Bean
        fun snsOutboxRouting(): SnsOutboxRouting =
            snsOutboxRouting {
                defaults {
                    target("arn:aws:sns:us-east-1:123456789012:custom-topic")
                }
            }
    }

    @Configuration
    class ConfigWithCustomHandler {
        @Bean
        fun snsOutboxHandler(): SnsOutboxHandler =
            SnsOutboxHandler(
                snsOperations = mockk(relaxed = true),
                routing =
                    snsOutboxRouting {
                        defaults { target("arn:aws:sns:us-east-1:123456789012:test") }
                    },
            )
    }
}
