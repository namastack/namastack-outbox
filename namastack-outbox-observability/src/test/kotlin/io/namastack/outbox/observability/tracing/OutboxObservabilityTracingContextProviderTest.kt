package io.namastack.outbox.observability.tracing

import io.micrometer.tracing.Span
import io.micrometer.tracing.TraceContext
import io.micrometer.tracing.Tracer
import io.micrometer.tracing.propagation.Propagator
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("OutboxObservabilityTracingContextProvider")
class OutboxObservabilityTracingContextProviderTest {
    private val tracer = mockk<Tracer>()
    private val span = mockk<Span>()
    private val traceContext = mockk<TraceContext>()
    private val propagator = mockk<Propagator>()

    private val provider = OutboxObservabilityTracingContextProvider(tracer, propagator)

    @Nested
    @DisplayName("Context Provision")
    inner class ContextProvision {
        @Test
        fun `returns empty context when no span is active`() {
            every { tracer.currentSpan() } returns null

            assertThat(provider.provide()).isEmpty()
        }

        @Test
        fun `injects active span context into map`() {
            every { tracer.currentSpan() } returns span
            every { span.context() } returns traceContext
            every { propagator.inject(traceContext, any<MutableMap<String, String>>(), any()) } answers {
                val carrier = secondArg<MutableMap<String, String>>()
                val setter = thirdArg<Propagator.Setter<MutableMap<String, String>>>()
                setter.set(carrier, "traceparent", "00-trace-span-01")
                setter.set(carrier, "tracestate", "vendor=value")
            }

            assertThat(provider.provide())
                .containsEntry("traceparent", "00-trace-span-01")
                .containsEntry("tracestate", "vendor=value")
        }

        @Test
        fun `returns empty context when propagation fails`() {
            every { tracer.currentSpan() } returns span
            every { span.context() } returns traceContext
            every { propagator.inject(traceContext, any<MutableMap<String, String>>(), any()) } throws
                IllegalStateException("propagation failed")

            assertThat(provider.provide()).isEmpty()
        }
    }
}
