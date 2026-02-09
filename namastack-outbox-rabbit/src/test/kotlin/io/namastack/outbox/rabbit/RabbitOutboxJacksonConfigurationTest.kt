package io.namastack.outbox.rabbit

import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter
import org.springframework.beans.factory.getBean
import org.springframework.boot.amqp.autoconfigure.RabbitTemplateCustomizer
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.json.JsonMapper

@DisplayName("RabbitOutboxJacksonConfiguration")
class RabbitOutboxJacksonConfigurationTest {
    private fun contextRunner() =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RabbitOutboxJacksonConfiguration::class.java))

    @Nested
    @DisplayName("rabbitTemplateCustomizer")
    inner class RabbitTemplateCustomizerTests {
        @Test
        fun `creates customizer bean by default`() {
            contextRunner().run { context ->
                assertThat(context).hasSingleBean(RabbitTemplateCustomizer::class.java)
            }
        }

        @Test
        fun `does not create customizer when enable-json is false`() {
            contextRunner()
                .withPropertyValues("namastack.outbox.rabbit.enable-json=false")
                .run { context ->
                    assertThat(context).doesNotHaveBean(RabbitTemplateCustomizer::class.java)
                }
        }

        @Test
        fun `configures RabbitTemplate to use JacksonJsonMessageConverter`() {
            contextRunner()
                .withUserConfiguration(ConfigWithRabbitTemplate::class.java)
                .run { context ->
                    val template = context.getBean<RabbitTemplate>()
                    val customizer = context.getBean<RabbitTemplateCustomizer>()

                    customizer.customize(template)

                    assertThat(template.messageConverter).isInstanceOf(JacksonJsonMessageConverter::class.java)
                }
        }
    }

    @Configuration
    class ConfigWithRabbitTemplate {
        @Bean
        fun rabbitTemplate() = RabbitTemplate(mockk(relaxed = true))
    }

    @Configuration
    class ConfigWithCustomJsonMapper {
        @Bean
        fun jsonMapper(): JsonMapper = JsonMapper.builder().build()
    }
}
