package io.namastack.outbox

import io.micrometer.tracing.Span
import io.micrometer.tracing.Tracer
import io.micrometer.tracing.propagation.Propagator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.OutboxRecordTestFactory.outboxRecord
import io.namastack.outbox.handler.method.handler.TypedHandlerMethod
import io.namastack.outbox.handler.registry.OutboxHandlerRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Method

class OutboxSpanFactoryTest {
    private val tracer = mockk<Tracer>()
    private val propagator = mockk<Propagator>()
    private val handlerRegistry = mockk<OutboxHandlerRegistry>()

    private lateinit var factory: OutboxSpanFactory

    @BeforeEach
    fun setUp() {
        factory = OutboxSpanFactory(tracer, propagator, handlerRegistry)
    }

    @Test
    fun `creates span with producer context and tags`() {
        val record =
            outboxRecord(
                id = "record-123",
                recordKey = "order-456",
                handlerId = "handler-1",
                context =
                    mapOf(
                        "traceparent" to "00-trace-span-01",
                        "tracestate" to "vendor=value",
                        "baggage" to "userId=123",
                    ),
                failureCount = 2,
            )

        val handler = createHandlerMethod()
        val spanBuilder = mockPropagator()

        every { handlerRegistry.getHandlerById("handler-1") } returns handler
        every { tracer.currentSpan() } returns null

        val result = factory.create(record)

        assertThat(result).isNotNull
        verify(exactly = 1) { spanBuilder.name("outbox process") }
        verify(exactly = 1) { spanBuilder.kind(Span.Kind.CONSUMER) }
        verify(exactly = 1) { spanBuilder.tag("outbox.record.id", "record-123") }
        verify(exactly = 1) { spanBuilder.tag("outbox.record.key", "order-456") }
        verify(exactly = 1) {
            spanBuilder.tag(
                "outbox.handler.class",
                "io.namastack.outbox.OutboxSpanFactoryTest\$TestHandler",
            )
        }
        verify(exactly = 1) { spanBuilder.tag("outbox.handler.method", "handleEvent") }
        verify(exactly = 1) { spanBuilder.tag("outbox.delivery.attempt", 3L) }
    }

    @Test
    fun `adds link to current span when exists`() {
        val record =
            outboxRecord(
                context = mapOf("traceparent" to "00-abc-def-01"),
            )

        val handler = createHandlerMethod()
        val spanBuilder = mockPropagator()
        val currentSpan = mockk<Span>(relaxed = true)

        every { handlerRegistry.getHandlerById(any()) } returns handler
        every { tracer.currentSpan() } returns currentSpan
        every { spanBuilder.addLink(any()) } returns spanBuilder

        val result = factory.create(record)

        assertThat(result).isNotNull
        verify(exactly = 1) { spanBuilder.addLink(any()) }
    }

    @Test
    fun `does not add link when no current span`() {
        val record =
            outboxRecord(
                context = mapOf("traceparent" to "00-abc-def-01"),
            )

        val handler = createHandlerMethod()
        val spanBuilder = mockPropagator()

        every { handlerRegistry.getHandlerById(any()) } returns handler
        every { tracer.currentSpan() } returns null

        val result = factory.create(record)

        assertThat(result).isNotNull
        verify(exactly = 0) { spanBuilder.addLink(any()) }
    }

    @Test
    fun `returns null when no trace context found`() {
        val record = outboxRecord(context = emptyMap())

        mockPropagator()

        val result = factory.create(record)

        assertThat(result).isNull()
    }

    @Test
    fun `returns null when propagator throws exception`() {
        val record =
            outboxRecord(
                context = mapOf("traceparent" to "00-abc-def-01"),
            )

        every { propagator.extract<Map<String, String>>(any(), any()) } throws RuntimeException("Propagation failed")

        val result = factory.create(record)

        assertThat(result).isNull()
    }

    @Test
    fun `returns null when handler not found`() {
        val record =
            outboxRecord(
                handlerId = "missing-handler",
                context = mapOf("traceparent" to "00-abc-def-01"),
            )

        mockPropagator()

        every { handlerRegistry.getHandlerById("missing-handler") } returns null

        val result = factory.create(record)

        assertThat(result).isNull()
    }

    @Test
    fun `returns null when handler registry throws exception`() {
        val record =
            outboxRecord(
                context = mapOf("traceparent" to "00-abc-def-01"),
            )

        mockPropagator()

        every { handlerRegistry.getHandlerById(any()) } throws RuntimeException("Registry failed")

        val result = factory.create(record)

        assertThat(result).isNull()
    }

    private fun mockPropagator(): Span.Builder {
        val spanBuilder = mockk<Span.Builder>(relaxed = true)

        every { propagator.extract<Map<String, String>>(any(), any()) } answers {
            val carrier = firstArg<Map<String, String>>()
            val getter = secondArg<Propagator.Getter<Map<String, String>>>()
            // Simulate extraction
            getter.get(carrier, "traceparent")
            getter.get(carrier, "tracestate")
            getter.get(carrier, "baggage")
            spanBuilder
        }
        every { spanBuilder.name(any()) } returns spanBuilder
        every { spanBuilder.kind(any()) } returns spanBuilder
        every { spanBuilder.tag(any<String>(), any<String>()) } returns spanBuilder
        every { spanBuilder.tag(any<String>(), any<Long>()) } returns spanBuilder

        return spanBuilder
    }

    private fun createHandlerMethod(): TypedHandlerMethod {
        val testHandler = TestHandler()
        val method: Method = TestHandler::class.java.getMethod("handleEvent", String::class.java)
        return TypedHandlerMethod(
            bean = testHandler,
            method = method,
        )
    }

    @Suppress("UNUSED_PARAMETER")
    class TestHandler {
        fun handleEvent(payload: String) {
            // Test handler
        }
    }
}
