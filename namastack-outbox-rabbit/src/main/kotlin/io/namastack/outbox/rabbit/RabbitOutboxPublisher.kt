package io.namastack.outbox.rabbit

import org.slf4j.LoggerFactory
import org.springframework.amqp.AmqpException
import org.springframework.amqp.core.MessagePostProcessor
import org.springframework.amqp.core.ReturnedMessage
import org.springframework.amqp.rabbit.connection.CorrelationData
import org.springframework.amqp.rabbit.core.RabbitOperations
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Publishes prepared outbox messages to RabbitMQ and waits for broker confirmation.
 *
 * This publisher expects Spring AMQP publisher confirms and returns to be configured by
 * the application, for example with `publisher-confirm-type=correlated`,
 * `publisher-returns=true`, and `template.mandatory=true`.
 *
 * @param rabbitOperations Spring AMQP operations used to publish messages
 * @param confirmTimeout maximum time to wait for the correlated publisher confirm
 * @author Roland Beisel
 * @since 1.7.0
 */
class RabbitOutboxPublisher(
    private val rabbitOperations: RabbitOperations,
    private val confirmTimeout: Duration = DEFAULT_CONFIRM_TIMEOUT,
) {
    private val logger = LoggerFactory.getLogger(RabbitOutboxPublisher::class.java)

    /**
     * Publishes a prepared outbox message and blocks until RabbitMQ confirms the publish.
     *
     * @param message prepared Rabbit outbox message
     * @throws RabbitOutboxSendException when RabbitMQ nacks or returns the message, the
     * confirm does not arrive before [confirmTimeout], sending fails, or waiting is interrupted
     */
    fun publish(message: RabbitOutboxMessage) {
        val correlationData = CorrelationData(UUID.randomUUID().toString())

        try {
            send(message, correlationData)
            verifyConfirm(correlationData)
        } catch (ex: RabbitOutboxSendException) {
            logFailure(message, ex)
            throw ex
        } catch (ex: AmqpException) {
            val wrapped = RabbitOutboxSendException("RabbitMQ send failed", ex)
            logFailure(message, wrapped)
            throw wrapped
        }
    }

    private fun send(
        message: RabbitOutboxMessage,
        correlationData: CorrelationData,
    ) {
        rabbitOperations.convertAndSend(
            message.exchange,
            message.routingKey,
            message.payload,
            messagePostProcessor(message.headers),
            correlationData,
        )
    }

    private fun verifyConfirm(correlationData: CorrelationData) {
        val confirm = waitForConfirm(correlationData)
        val returned = correlationData.returned
        if (returned != null) {
            throw returnedMessageException(returned)
        }

        if (!confirm.ack()) {
            throw RabbitOutboxSendException(
                "RabbitMQ publisher confirm was nacked: ${confirm.reason() ?: "no reason"}",
            )
        }
    }

    private fun waitForConfirm(correlationData: CorrelationData): CorrelationData.Confirm =
        try {
            correlationData.future.get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (ex: TimeoutException) {
            throw RabbitOutboxSendException("Timed out waiting for RabbitMQ publisher confirm", ex)
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RabbitOutboxSendException("Interrupted while waiting for RabbitMQ publisher confirm", ex)
        } catch (ex: ExecutionException) {
            throw RabbitOutboxSendException("Failed while waiting for RabbitMQ publisher confirm", ex.cause ?: ex)
        }

    private fun returnedMessageException(returned: ReturnedMessage): RabbitOutboxSendException =
        RabbitOutboxSendException(
            "RabbitMQ message was returned: exchange=${returned.exchange}, " +
                "routingKey=${returned.routingKey}, replyCode=${returned.replyCode}, replyText=${returned.replyText}",
        )

    private fun messagePostProcessor(headers: Map<String, String>): MessagePostProcessor =
        MessagePostProcessor { message ->
            headers.forEach { (name, value) ->
                message.messageProperties.setHeader(name, value)
            }
            message
        }

    private fun logFailure(
        message: RabbitOutboxMessage,
        exception: RabbitOutboxSendException,
    ) {
        logger.error(
            "Failed to send outbox record to Rabbit: exchange={}, routingKey={}, handlerId={}",
            message.exchange,
            message.routingKey,
            message.handlerId,
            exception,
        )
    }

    private companion object {
        val DEFAULT_CONFIRM_TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
