package io.namastack.outbox.kafka

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.routing.selector.OutboxPayloadSelector
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaOperations
import org.springframework.kafka.support.SendResult
import java.time.Instant
import java.util.concurrent.CompletableFuture

class KafkaOutboxHandlerTest {
    private lateinit var kafkaOperations: KafkaOperations<String, Any>
    private lateinit var routingConfiguration: KafkaRoutingConfiguration
    private lateinit var handler: KafkaOutboxHandler

    private val metadata =
        OutboxRecordMetadata(
            key = "order-123",
            handlerId = "test-handler",
            createdAt = Instant.now(),
            context = mapOf("tenant" to "acme", "correlationId" to "corr-456"),
        )

    @BeforeEach
    fun setUp() {
        kafkaOperations = mockk()
        routingConfiguration =
            KafkaRoutingConfiguration
                .create()
                .routing {
                    route(OutboxPayloadSelector.type(OrderCreatedEvent::class.java)) {
                        topic("orders")
                        key { payload, _ -> (payload as OrderCreatedEvent).orderId }
                        headers { _, meta -> meta.context }
                    }
                    defaults {
                        topic("domain-events")
                    }
                }
        handler = KafkaOutboxHandler(kafkaOperations, routingConfiguration)
    }

    @Test
    fun `sends payload to correct topic`() {
        val recordSlot = slot<ProducerRecord<String, Any>>()
        every { kafkaOperations.send(capture(recordSlot)) } returns completedFuture()

        val payload = OrderCreatedEvent("order-123")
        handler.handle(payload, metadata)

        verify { kafkaOperations.send(any<ProducerRecord<String, Any>>()) }
        assertThat(recordSlot.captured.topic()).isEqualTo("orders")
    }

    @Test
    fun `sends payload with correct key`() {
        val recordSlot = slot<ProducerRecord<String, Any>>()
        every { kafkaOperations.send(capture(recordSlot)) } returns completedFuture()

        val payload = OrderCreatedEvent("my-order-id")
        handler.handle(payload, metadata)

        assertThat(recordSlot.captured.key()).isEqualTo("my-order-id")
    }

    @Test
    fun `sends payload with headers from configuration`() {
        val recordSlot = slot<ProducerRecord<String, Any>>()
        every { kafkaOperations.send(capture(recordSlot)) } returns completedFuture()

        val payload = OrderCreatedEvent("order-123")
        handler.handle(payload, metadata)

        val headers = recordSlot.captured.headers()
        assertThat(headers.lastHeader("tenant")?.value()?.toString(Charsets.UTF_8)).isEqualTo("acme")
        assertThat(headers.lastHeader("correlationId")?.value()?.toString(Charsets.UTF_8)).isEqualTo("corr-456")
    }

    @Test
    fun `sends payload as value`() {
        val recordSlot = slot<ProducerRecord<String, Any>>()
        every { kafkaOperations.send(capture(recordSlot)) } returns completedFuture()

        val payload = OrderCreatedEvent("order-123")
        handler.handle(payload, metadata)

        assertThat(recordSlot.captured.value()).isSameAs(payload)
    }

    @Test
    fun `uses default route for unmatched payload types`() {
        val recordSlot = slot<ProducerRecord<String, Any>>()
        every { kafkaOperations.send(capture(recordSlot)) } returns completedFuture()

        val payload = "some-string-payload"
        handler.handle(payload, metadata)

        assertThat(recordSlot.captured.topic()).isEqualTo("domain-events")
    }

    @Test
    fun `uses metadata key when no custom key extractor`() {
        val recordSlot = slot<ProducerRecord<String, Any>>()
        every { kafkaOperations.send(capture(recordSlot)) } returns completedFuture()

        // String payload uses default route which doesn't have custom key extractor
        val payload = "some-string-payload"
        handler.handle(payload, metadata)

        assertThat(recordSlot.captured.key()).isEqualTo("order-123")
    }

    @Test
    fun `throws exception when kafka send fails`() {
        val cause = RuntimeException("Kafka connection failed")
        every { kafkaOperations.send(any<ProducerRecord<String, Any>>()) } returns failedFuture(cause)

        val payload = OrderCreatedEvent("order-123")

        assertThatThrownBy { handler.handle(payload, metadata) }
            .isSameAs(cause)
    }

    @Test
    fun `throws exception when kafka send fails with checked exception`() {
        val cause = Exception("Kafka error")
        every { kafkaOperations.send(any<ProducerRecord<String, Any>>()) } returns failedFuture(cause)

        val payload = OrderCreatedEvent("order-123")

        assertThatThrownBy { handler.handle(payload, metadata) }
            .isSameAs(cause)
    }

    private fun completedFuture(): CompletableFuture<SendResult<String, Any>> {
        val recordMetadata =
            RecordMetadata(
                TopicPartition("test-topic", 0),
                0L,
                0,
                0L,
                0,
                0,
            )
        val producerRecord = ProducerRecord<String, Any>("test-topic", "test-key", "test-value")
        val sendResult = SendResult(producerRecord, recordMetadata)
        return CompletableFuture.completedFuture(sendResult)
    }

    private fun failedFuture(cause: Throwable): CompletableFuture<SendResult<String, Any>> {
        val future = CompletableFuture<SendResult<String, Any>>()
        future.completeExceptionally(cause)
        return future
    }

    data class OrderCreatedEvent(
        val orderId: String,
    )
}
