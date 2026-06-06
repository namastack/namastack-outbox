package io.namastack.outbox.rabbit

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

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
     * Whether to configure RabbitTemplate to use Jackson JSON message conversion.
     */
    var enableJson: Boolean = true,
    /**
     * Default Rabbit exchange for outbox events.
     */
    var defaultExchange: String = "outbox-events",
    /**
     * Maximum time to wait for RabbitMQ publisher confirms.
     */
    var publisherConfirmTimeout: Duration = Duration.ofSeconds(10),
    /**
     * Whether unroutable messages should fail outbox processing.
     *
     * When enabled, Spring AMQP publisher returns and mandatory publishing must also be enabled.
     */
    var failOnUnroutable: Boolean = false,
)
