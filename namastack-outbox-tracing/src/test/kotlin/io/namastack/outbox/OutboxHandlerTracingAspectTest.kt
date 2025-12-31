package io.namastack.outbox

import io.micrometer.tracing.Span
import io.micrometer.tracing.Tracer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.OutboxRecordTestFactory.outboxRecord
import org.aspectj.lang.ProceedingJoinPoint
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OutboxHandlerTracingAspectTest {
    private val spanFactory = mockk<OutboxSpanFactory>()
    private val tracer = mockk<Tracer>()
    private val joinPoint = mockk<ProceedingJoinPoint>()

    private lateinit var aspect: OutboxHandlerTracingAspect

    @BeforeEach
    fun setUp() {
        aspect = OutboxHandlerTracingAspect(spanFactory, tracer)
    }

    @Test
    fun `proceeds without span when no trace context found`() {
        val record = outboxRecord(context = emptyMap())

        every { spanFactory.create(record) } returns null
        every { joinPoint.proceed() } returns "result"

        val result = aspect.traceRecordProcessing(joinPoint, record)

        assertThat(result).isEqualTo("result")
        verify(exactly = 1) { spanFactory.create(record) }
        verify(exactly = 0) { tracer.withSpan(any()) }
        verify(exactly = 1) { joinPoint.proceed() }
    }

    @Test
    fun `creates and uses span when trace context exists`() {
        val record =
            outboxRecord(
                id = "test-id",
                context = mapOf("traceparent" to "00-abc-def-01"),
            )

        val span = mockk<Span>(relaxed = true)
        val scope = mockk<Tracer.SpanInScope>(relaxed = true)

        every { spanFactory.create(record) } returns span
        every { tracer.withSpan(span) } returns scope
        every { joinPoint.proceed() } returns "result"

        val result = aspect.traceRecordProcessing(joinPoint, record)

        assertThat(result).isEqualTo("result")
        verify(exactly = 1) { spanFactory.create(record) }
        verify(exactly = 1) { tracer.withSpan(span) }
        verify(exactly = 1) { joinPoint.proceed() }
        verify(exactly = 1) { scope.close() }
        verify(exactly = 1) { span.end() }
        verify(exactly = 0) { span.error(any<Throwable>()) }
    }

    @Test
    fun `records failure exception in span when present`() {
        val failureException = RuntimeException("Processing failed")
        val record =
            outboxRecord(
                context = mapOf("traceparent" to "00-abc-def-01"),
                failureException = failureException,
            )

        val span = mockk<Span>(relaxed = true)
        val scopedTracer = mockk<Tracer.SpanInScope>(relaxed = true)

        every { spanFactory.create(record) } returns span
        every { tracer.withSpan(span) } returns scopedTracer
        every { joinPoint.proceed() } returns "result"

        val result = aspect.traceRecordProcessing(joinPoint, record)

        assertThat(result).isEqualTo("result")
        verify(exactly = 1) { span.error(failureException) }
        verify(exactly = 1) { span.end() }
    }

    @Test
    fun `ends span even when joinPoint throws exception`() {
        val record =
            outboxRecord(
                context = mapOf("traceparent" to "00-abc-def-01"),
            )

        val exception = RuntimeException("Handler failed")
        val span = mockk<Span>(relaxed = true)
        val scope = mockk<Tracer.SpanInScope>(relaxed = true)

        every { spanFactory.create(record) } returns span
        every { tracer.withSpan(span) } returns scope
        every { joinPoint.proceed() } throws exception

        assertThatThrownBy {
            aspect.traceRecordProcessing(joinPoint, record)
        }.isEqualTo(exception)

        verify(exactly = 1) { scope.close() }
        verify(exactly = 1) { span.end() }
    }
}
