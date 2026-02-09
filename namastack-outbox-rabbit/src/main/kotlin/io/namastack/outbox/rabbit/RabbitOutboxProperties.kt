package io.namastack.outbox.rabbit

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for Rabbit outbox integration.
 *
 * @author Roland Beisel
 * @since 1.1.0
 */
@ConfigurationProperties("namastack.outbox.rabbit")
data class RabbitOutboxProperties(
    /**
     * Whether Rabbit exchange integration is enabled.
     */
    var enabled: Boolean = true,
    /**
     * Default Rabbit exchange for outbox events.
     */
    var defaultExchange: String = "outbox-events",
)
