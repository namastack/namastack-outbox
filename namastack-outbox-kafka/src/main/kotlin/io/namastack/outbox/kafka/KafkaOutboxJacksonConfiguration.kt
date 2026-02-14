package io.namastack.outbox.kafka

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigureBefore
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration
import org.springframework.context.annotation.PropertySource
import org.springframework.kafka.core.KafkaTemplate

@AutoConfiguration
@AutoConfigureBefore(KafkaAutoConfiguration::class)
@ConditionalOnClass(KafkaTemplate::class)
@ConditionalOnProperty(
    name = ["namastack.outbox.kafka.enable-json"],
    havingValue = "true",
    matchIfMissing = true,
)
@PropertySource("classpath:kafka-json.properties")
class KafkaOutboxJacksonConfiguration
