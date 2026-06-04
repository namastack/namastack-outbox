package io.namastack.outbox.rabbit

/**
 * Message prepared by [RabbitOutboxHandler] for publication through [RabbitOutboxPublisher].
 *
 * @property payload payload to publish after routing-specific mapping
 * @property exchange RabbitMQ exchange to publish to
 * @property routingKey normalized RabbitMQ routing key, using an empty string when no key is configured
 * @property headers message headers to apply during publication
 * @property handlerId outbox handler id for logging and diagnostics only
 * @author Roland Beisel
 * @since 1.7.0
 */
data class RabbitOutboxMessage(
    val payload: Any,
    val exchange: String,
    val routingKey: String,
    val headers: Map<String, String>,
    val handlerId: String?,
)
