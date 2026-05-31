package io.namastack.demo

import io.namastack.outbox.JacksonOutboxPayloadSerializer
import io.namastack.outbox.OutboxPayloadSerializer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

/**
 * Registers both serializers explicitly.
 *
 * When mixing serializers you must declare the global default (Jackson) as @Primary and your
 * custom serializer as a separately-named bean. Declaring any bean that implements
 * OutboxPayloadSerializer suppresses the auto-configured Jackson default, so both must be
 * declared here together.
 */
@Configuration
class SerializerConfig {
    @Bean
    @Primary
    fun jacksonSerializer(): OutboxPayloadSerializer =
        JacksonOutboxPayloadSerializer(JsonMapper.builder().addModule(kotlinModule()).build())

    @Bean
    fun protobufSerializer(): ProtobufOutboxPayloadSerializer = ProtobufOutboxPayloadSerializer()
}
