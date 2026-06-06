package io.namastack.outbox.rabbit

import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageProperties
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitOperations
import org.springframework.amqp.rabbit.core.RabbitTemplate

/**
 * Validates that Spring AMQP is configured for synchronous outbox publication.
 *
 * The outbox publisher relies on correlated publisher confirms to determine whether
 * RabbitMQ accepted a specific message. When unroutable messages should fail outbox
 * processing, publisher returns and mandatory publishing are required as well. Settings
 * are validated against the connection factory used by the selected [RabbitOperations].
 *
 * @author Roland Beisel
 * @since 1.7.0
 */
object RabbitOutboxPublisherSettingsValidator {
    /**
     * Verifies that the configured Spring AMQP infrastructure can support synchronous
     * Rabbit outbox publication.
     *
     * @param rabbitOperations operations used by [RabbitOutboxPublisher] to publish messages
     * @param failOnUnroutable whether returned messages should fail outbox processing
     * @throws IllegalStateException when correlated publisher confirms are not enabled, or
     * when publisher returns or mandatory publishing are missing while [failOnUnroutable] is enabled
     */
    fun validate(
        rabbitOperations: RabbitOperations,
        failOnUnroutable: Boolean,
    ) {
        val connectionFactory = rabbitOperations.connectionFactory
        val missingSettings =
            buildList {
                if (!hasCorrelatedPublisherConfirms(connectionFactory)) {
                    add("spring.rabbitmq.publisher-confirm-type=correlated")
                }
                if (failOnUnroutable && !connectionFactory.isPublisherReturns) {
                    add("spring.rabbitmq.publisher-returns=true")
                }
                if (failOnUnroutable && !hasMandatoryTemplate(rabbitOperations)) {
                    add("spring.rabbitmq.template.mandatory=true")
                }
            }

        if (missingSettings.isNotEmpty()) {
            throw IllegalStateException(
                "Rabbit outbox is not safely configured for synchronous publishing. " +
                    "Configure: ${missingSettings.joinToString(", ")}",
            )
        }
    }

    private fun hasCorrelatedPublisherConfirms(connectionFactory: ConnectionFactory): Boolean =
        connectionFactory.isPublisherConfirms && !connectionFactory.isSimplePublisherConfirms

    private fun hasMandatoryTemplate(rabbitOperations: RabbitOperations): Boolean =
        rabbitOperations is RabbitTemplate &&
            rabbitOperations.isMandatoryFor(
                Message(
                    ByteArray(0),
                    MessageProperties(),
                ),
            )
}
