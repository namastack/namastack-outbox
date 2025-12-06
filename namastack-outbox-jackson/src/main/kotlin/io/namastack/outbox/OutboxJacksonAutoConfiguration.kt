package io.namastack.outbox

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigureBefore
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import tools.jackson.databind.json.JsonMapper

/**
 * Spring Boot auto-configuration for Jackson-based event serialization.
 *
 * Provides OutboxPayloadSerializer bean for outbox event persistence.
 * Uses an existing JsonMapper bean if available, otherwise creates a default one.
 * Loads before OutboxCoreAutoConfiguration to ensure serializer is available.
 *
 * @author Roland Beisel
 * @since 0.3.0
 */
@AutoConfiguration
@AutoConfigureBefore(OutboxCoreAutoConfiguration::class)
class OutboxJacksonAutoConfiguration {
    /**
     * Creates OutboxPayloadSerializer bean using Jackson.
     *
     * Uses an existing JsonMapper bean if available in the application context.
     * If no JsonMapper bean exists, creates a default one.
     *
     * @param jsonMapperProvider Optional provider for custom JsonMapper bean
     * @return JacksonOutboxPayloadSerializer instance
     */
    @Bean
    @ConditionalOnMissingBean(OutboxPayloadSerializer::class)
    fun outboxPayloadSerializer(jsonMapperProvider: ObjectProvider<JsonMapper>): OutboxPayloadSerializer {
        val mapper = jsonMapperProvider.getIfAvailable { JsonMapper.builder().build() }

        return JacksonOutboxPayloadSerializer(mapper = mapper)
    }
}
