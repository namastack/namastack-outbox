package io.namastack.outbox.kafka

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for Kafka outbox integration.
 *
 * @author Roland Beisel
 * @since 1.1.0
 */
@ConfigurationProperties("namastack.outbox.kafka")
data class KafkaOutboxProperties(
    /**
     * Whether Kafka outbox integration is enabled.
     */
    var enabled: Boolean = true,
    /**
     * Default Kafka topic for outbox events.
     */
    var defaultTopic: String = "outbox-events",
)
