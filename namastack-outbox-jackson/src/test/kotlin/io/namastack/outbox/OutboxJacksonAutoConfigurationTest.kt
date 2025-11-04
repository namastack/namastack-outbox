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
import tools.jackson.databind.json.JsonMapper

@DisplayName("OutboxJacksonAutoConfiguration")
class OutboxJacksonAutoConfigurationTest {
    private fun contextRunner() =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxJacksonAutoConfiguration::class.java))

    @Nested
    @DisplayName("JsonMapper Bean")
    inner class JsonMapperBean {
        @Test
        fun `creates default JsonMapper bean`() {
            contextRunner()
                .run { context ->
                    assertThat(context).hasSingleBean(JsonMapper::class.java)
                }
        }

        @Test
        fun `JsonMapper bean is not null`() {
            contextRunner()
                .run { context ->
                    val mapper = context.getBean(JsonMapper::class.java)
                    assertThat(mapper).isNotNull()
                }
        }
    }

    @Nested
    @DisplayName("OutboxEventSerializer Bean")
    inner class OutboxEventSerializerBean {
        @Test
        fun `creates JacksonEventOutboxSerializer bean`() {
            contextRunner()
                .run { context ->
                    assertThat(context).hasSingleBean(OutboxEventSerializer::class.java)
                    assertThat(context.getBean(OutboxEventSerializer::class.java))
                        .isInstanceOf(JacksonEventOutboxSerializer::class.java)
                }
        }

        @Test
        fun `does not create serializer when one is already provided`() {
            contextRunner()
                .withUserConfiguration(ConfigWithCustomSerializer::class.java)
                .run { context ->
                    assertThat(context).hasSingleBean(OutboxEventSerializer::class.java)
                    assertThat(context.getBean(OutboxEventSerializer::class.java))
                        .isInstanceOf(CustomEventSerializer::class.java)
                }
        }

        @Test
        fun `serializer is injected with JsonMapper`() {
            contextRunner()
                .run { context ->
                    val serializer = context.getBean(OutboxEventSerializer::class.java) as JacksonEventOutboxSerializer
                    assertThat(serializer).isNotNull()
                }
        }
    }

    @Nested
    @DisplayName("Configuration Conditions")
    inner class ConfigurationConditions {
        @Test
        fun `configuration is active when on classpath`() {
            contextRunner()
                .run { context ->
                    assertThat(context).hasNotFailed()
                    assertThat(context).hasSingleBean(JsonMapper::class.java)
                    assertThat(context).hasSingleBean(OutboxEventSerializer::class.java)
                }
        }

        @Test
        fun `bean creation order is correct - JsonMapper before serializer`() {
            contextRunner()
                .run { context ->
                    val mapper = context.getBean(JsonMapper::class.java)
                    val serializer = context.getBean(OutboxEventSerializer::class.java)
                    assertThat(mapper).isNotNull()
                    assertThat(serializer).isNotNull()
                }
        }
    }

    @Configuration
    private class ConfigWithCustomSerializer {
        @Bean
        fun outboxEventSerializer(): OutboxEventSerializer = CustomEventSerializer()
    }

    private class CustomEventSerializer : OutboxEventSerializer {
        override fun serialize(outboxEvent: Any): String = ""

        override fun <T> deserialize(
            serialized: String,
            type: Class<T>,
        ): T = mockk()
    }
}
