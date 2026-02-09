package io.namastack.outbox.rabbit

import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter
import org.springframework.boot.amqp.autoconfigure.RabbitTemplateCustomizer
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import tools.jackson.databind.json.JsonMapper

@AutoConfiguration
@ConditionalOnClass(RabbitTemplate::class, JsonMapper::class)
@ConditionalOnProperty(
    name = ["namastack.outbox.rabbit.enable-json"],
    havingValue = "true",
    matchIfMissing = true,
)
class RabbitOutboxJacksonConfiguration {
    @Bean
    fun rabbitTemplateCustomizer(jsonMapper: JsonMapper): RabbitTemplateCustomizer =
        RabbitTemplateCustomizer { template: RabbitTemplate ->
            template.messageConverter = JacksonJsonMessageConverter(jsonMapper)
        }
}
