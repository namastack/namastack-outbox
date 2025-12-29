package io.namastack.outbox

import io.micrometer.tracing.Span
import io.micrometer.tracing.TraceContext
import io.micrometer.tracing.Tracer
import io.micrometer.tracing.propagation.Propagator
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OutboxTracingContextProviderTest {
    private val tracer = mockk<Tracer>()
    private val propagator = mockk<Propagator>()
    private val provider = OutboxTracingContextProvider(tracer, propagator)

    @Test
    fun `returns empty map when no current span exists`() {
        every { tracer.currentSpan() } returns null

        val result = provider.provide()

        assertEquals(emptyMap<String, String>(), result)
        verify { propagator wasNot Called }
    }

    @Test
    fun `injects trace context when span exists`() {
        val span = mockk<Span>()
        val context = mockk<TraceContext>()

        every { tracer.currentSpan() } returns span
        every { span.context() } returns context

        every {
            propagator.inject(
                context,
                any<MutableMap<String, String>>(),
                any(),
            )
        } answers {
            val carrier = secondArg<MutableMap<String, String>>()
            carrier["traceparent"] = "00-abc-def-01"
            carrier["tracestate"] = "vendor=value"
        }

        val result = provider.provide()

        assertEquals(
            mapOf(
                "traceparent" to "00-abc-def-01",
                "tracestate" to "vendor=value",
            ),
            result,
        )
    }
}
