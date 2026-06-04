package io.namastack.outbox.rabbit

/**
 * Raised when RabbitMQ does not accept an outbox message for publication.
 *
 * @param message failure description suitable for logs and diagnostics
 * @param cause underlying RabbitMQ, AMQP, timeout, or interruption failure
 * @author Roland Beisel
 * @since 1.7.0
 */
class RabbitOutboxSendException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
