package io.namastack.outbox.config

import io.namastack.outbox.OutboxPayloadSerializer
import io.namastack.outbox.event.OutboxEventTypeRegistry
import io.namastack.outbox.event.OutboxRecordTypeResolver
import io.namastack.outbox.serializer.OutboxPayloadSerializerRegistry
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

@TestConfiguration
class OutboxCoreTestConfiguration {
    @Bean
    fun outboxEventTypeRegistry(): OutboxEventTypeRegistry = OutboxEventTypeRegistry()

    @Bean
    fun outboxRecordTypeResolver(registry: OutboxEventTypeRegistry): OutboxRecordTypeResolver =
        OutboxRecordTypeResolver(registry)

    @Bean
    fun outboxPayloadSerializerRegistry(serializer: OutboxPayloadSerializer): OutboxPayloadSerializerRegistry =
        OutboxPayloadSerializerRegistry(serializer, emptyMap())
}
