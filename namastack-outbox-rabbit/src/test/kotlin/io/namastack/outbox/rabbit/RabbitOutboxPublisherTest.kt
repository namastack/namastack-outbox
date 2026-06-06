package io.namastack.outbox.rabbit

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.amqp.AmqpException
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessagePostProcessor
import org.springframework.amqp.core.MessageProperties
import org.springframework.amqp.core.ReturnedMessage
import org.springframework.amqp.rabbit.connection.CorrelationData
import org.springframework.amqp.rabbit.core.RabbitOperations
import java.time.Duration
import java.util.UUID

@DisplayName("RabbitOutboxPublisher")
class RabbitOutboxPublisherTest {
    private lateinit var rabbitOperations: RabbitOperations
    private lateinit var publisher: RabbitOutboxPublisher

    private val message =
        RabbitOutboxMessage(
            payload = "payload",
            exchange = "events",
            routingKey = "order-123",
            headers = mapOf("tenant" to "acme"),
            handlerId = "test-handler",
        )

    @BeforeEach
    fun setUp() {
        rabbitOperations = mockk()
        publisher = RabbitOutboxPublisher(rabbitOperations, Duration.ofSeconds(1))
    }

    @AfterEach
    fun tearDown() {
        Thread.interrupted()
    }

    @Test
    fun `sends payload successfully`() {
        everySuccessfulSend()

        publisher.publish(message)
    }

    @Test
    fun `applies headers`() {
        val postProcessorSlot = slot<MessagePostProcessor>()
        val correlationDataSlot = slot<CorrelationData>()
        every {
            rabbitOperations.convertAndSend(
                message.exchange,
                message.routingKey,
                message.payload,
                capture(postProcessorSlot),
                capture(correlationDataSlot),
            )
        } answers {
            completeConfirm(correlationDataSlot.captured, CorrelationData.Confirm(true, null))
        }

        publisher.publish(message)

        val amqpMessage = Message(ByteArray(0), MessageProperties())
        postProcessorSlot.captured.postProcessMessage(amqpMessage)
        assertThat(amqpMessage.messageProperties.headers).containsEntry("tenant", "acme")
    }

    @Test
    fun `generates a unique correlation id`() {
        val correlationData = mutableListOf<CorrelationData>()
        every {
            rabbitOperations.convertAndSend(
                any<String>(),
                any<String>(),
                any(),
                any<MessagePostProcessor>(),
                any<CorrelationData>(),
            )
        } answers {
            val captured = arg<CorrelationData>(4)
            correlationData += captured
            completeConfirm(captured, CorrelationData.Confirm(true, null))
        }

        publisher.publish(message)
        publisher.publish(message)

        assertThat(correlationData).hasSize(2)
        assertThat(correlationData[0].id).isNotEqualTo(correlationData[1].id)
        correlationData.forEach {
            assertThat(UUID.fromString(it.id).toString()).isEqualTo(it.id)
        }
    }

    @Test
    fun `throws on nack`() {
        everySendCompletesWith(CorrelationData.Confirm(false, "exchange missing"))

        assertThatThrownBy { publisher.publish(message) }
            .isInstanceOf(RabbitOutboxSendException::class.java)
            .hasMessageContaining("nacked")
            .hasMessageContaining("exchange missing")
    }

    @Test
    fun `ignores returned message by default`() {
        everySendReturnsMessage()

        publisher.publish(message)
    }

    @Test
    fun `throws on returned message when fail on unroutable is enabled`() {
        publisher = RabbitOutboxPublisher(rabbitOperations, Duration.ofSeconds(1), failOnUnroutable = true)
        everySendReturnsMessage()

        assertThatThrownBy { publisher.publish(message) }
            .isInstanceOf(RabbitOutboxSendException::class.java)
            .hasMessageContaining("returned")
            .hasMessageContaining("NO_ROUTE")
    }

    @Test
    fun `throws on timeout`() {
        publisher = RabbitOutboxPublisher(rabbitOperations, Duration.ZERO)
        everySendLeavesConfirmPending()

        assertThatThrownBy { publisher.publish(message) }
            .isInstanceOf(RabbitOutboxSendException::class.java)
            .hasMessageContaining("Timed out")
            .hasCauseInstanceOf(java.util.concurrent.TimeoutException::class.java)
    }

    @Test
    fun `restores interrupt status on interruption`() {
        everySendLeavesConfirmPending()
        Thread.currentThread().interrupt()

        assertThatThrownBy { publisher.publish(message) }
            .isInstanceOf(RabbitOutboxSendException::class.java)
            .hasMessageContaining("Interrupted")
            .hasCauseInstanceOf(InterruptedException::class.java)

        assertThat(Thread.currentThread().isInterrupted).isTrue()
    }

    @Test
    fun `wraps confirm waiting failures in RabbitOutboxSendException`() {
        every {
            rabbitOperations.convertAndSend(
                any<String>(),
                any<String>(),
                any(),
                any<MessagePostProcessor>(),
                any<CorrelationData>(),
            )
        } answers {
            failConfirm(arg(4), IllegalStateException("confirm failed"))
        }

        assertThatThrownBy { publisher.publish(message) }
            .isInstanceOf(RabbitOutboxSendException::class.java)
            .hasMessageContaining("Failed while waiting")
            .hasCauseInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `wraps RabbitMQ send failures in RabbitOutboxSendException`() {
        every {
            rabbitOperations.convertAndSend(
                any<String>(),
                any<String>(),
                any(),
                any<MessagePostProcessor>(),
                any<CorrelationData>(),
            )
        } throws AmqpException("broker unavailable")

        assertThatThrownBy { publisher.publish(message) }
            .isInstanceOf(RabbitOutboxSendException::class.java)
            .hasMessageContaining("RabbitMQ send failed")
            .hasCauseInstanceOf(AmqpException::class.java)
    }

    private fun everySuccessfulSend() {
        everySendCompletesWith(CorrelationData.Confirm(true, null))
    }

    private fun everySendCompletesWith(confirm: CorrelationData.Confirm) {
        every {
            rabbitOperations.convertAndSend(
                any<String>(),
                any<String>(),
                any(),
                any<MessagePostProcessor>(),
                any<CorrelationData>(),
            )
        } answers {
            completeConfirm(arg(4), confirm)
        }
    }

    private fun everySendReturnsMessage() {
        every {
            rabbitOperations.convertAndSend(
                any<String>(),
                any<String>(),
                any(),
                any<MessagePostProcessor>(),
                any<CorrelationData>(),
            )
        } answers {
            val correlationData = arg<CorrelationData>(4)
            correlationData.setReturned(
                ReturnedMessage(
                    Message(ByteArray(0), MessageProperties()),
                    312,
                    "NO_ROUTE",
                    message.exchange,
                    message.routingKey,
                ),
            )
            completeConfirm(correlationData, CorrelationData.Confirm(true, null))
        }
    }

    private fun everySendLeavesConfirmPending() {
        every {
            rabbitOperations.convertAndSend(
                any<String>(),
                any<String>(),
                any(),
                any<MessagePostProcessor>(),
                any<CorrelationData>(),
            )
        } returns Unit
    }

    private fun completeConfirm(
        correlationData: CorrelationData,
        confirm: CorrelationData.Confirm,
    ) {
        correlationData.future.complete(confirm)
    }

    private fun failConfirm(
        correlationData: CorrelationData,
        failure: Throwable,
    ) {
        correlationData.future.completeExceptionally(failure)
    }
}
