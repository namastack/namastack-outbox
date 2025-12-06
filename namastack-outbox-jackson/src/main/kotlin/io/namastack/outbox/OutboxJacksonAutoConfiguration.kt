package io.namastack.outbox

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigureBefore
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import tools.jackson.databind.json.JsonMapper

/**
 * Spring Boot auto-configuration for Jackson-based event serialization.
 *
 * Provides JsonMapper and OutboxPayloadSerializer beans for outbox event persistence.
 * Loads before OutboxCoreAutoConfiguration to ensure serializer is available.
 *
 * @author Roland Beisel
 * @since 0.3.0
 */
@AutoConfiguration
@ConditionalOnMissingBean(OutboxPayloadSerializer::class)
@AutoConfigureBefore(OutboxCoreAutoConfiguration::class)
class OutboxJacksonAutoConfiguration {
    /**
     * Creates default Jackson JsonMapper for event serialization.
     *
     * @return Configured JsonMapper instance
     */
    @Bean
    fun jsonMapper(): JsonMapper = JsonMapper.builder().build()

    /**
     * Creates OutboxPayloadSerializer bean using Jackson.
     *
     * @param jsonMapper Jackson mapper for serialization operations
     * @return JacksonOutboxPayloadSerializer instance
     */
    @Bean
    fun outboxEventSerializer(jsonMapper: JsonMapper): OutboxPayloadSerializer =
        JacksonOutboxPayloadSerializer(jsonMapper)
}
