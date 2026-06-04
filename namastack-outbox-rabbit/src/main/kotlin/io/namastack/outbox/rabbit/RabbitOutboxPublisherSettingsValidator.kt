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
 * RabbitMQ accepted a specific message, publisher returns to detect unroutable messages,
 * and mandatory publishing to force unroutable messages through the returns path.
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
     * @param connectionFactory connection factory backing Rabbit publication
     * @throws IllegalStateException when correlated publisher confirms, publisher returns,
     * or mandatory publishing are not enabled
     */
    fun validate(
        rabbitOperations: RabbitOperations,
        connectionFactory: ConnectionFactory,
    ) {
        val missingSettings =
            buildList {
                if (!hasCorrelatedPublisherConfirms(connectionFactory)) {
                    add("spring.rabbitmq.publisher-confirm-type=correlated")
                }
                if (!connectionFactory.isPublisherReturns) {
                    add("spring.rabbitmq.publisher-returns=true")
                }
                if (!hasMandatoryTemplate(rabbitOperations)) {
                    add("spring.rabbitmq.template.mandatory=true")
                }
            }

        if (missingSettings.isNotEmpty()) {
            throw IllegalStateException(
                "Rabbit outbox requires publisher confirms, returns, and mandatory publishing. " +
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
