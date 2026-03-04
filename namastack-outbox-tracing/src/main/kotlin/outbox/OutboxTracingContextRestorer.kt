package io.namastack.outbox

import io.micrometer.tracing.Span
import io.micrometer.tracing.Tracer
import io.namastack.outbox.handler.OutboxFailureContext
import io.namastack.outbox.handler.OutboxFallbackHandlerInterceptor
import io.namastack.outbox.handler.OutboxHandlerInterceptor
import io.namastack.outbox.handler.OutboxRecordMetadata

class OutboxTracingContextRestorer(
    private val spanFactory: OutboxSpanFactory,
    private val tracer: Tracer,
) : OutboxHandlerInterceptor,
    OutboxFallbackHandlerInterceptor {
    override fun intercept(
        payload: Any,
        metadata: OutboxRecordMetadata,
        next: () -> Unit,
    ) {
        val span = spanFactory.createHandlerSpan(metadata) ?: return next()

        runWithSpan(span, next)
    }

    override fun intercept(
        payload: Any,
        context: OutboxFailureContext,
        next: () -> Unit,
    ) {
        val span = spanFactory.createFallbackHandlerSpan(context) ?: return next()

        runWithSpan(span, next)
    }

    private fun runWithSpan(
        span: Span,
        next: () -> Unit,
    ) {
        try {
            return tracer.withSpan(span).use { next() }
        } catch (ex: Exception) {
            span.error(ex)
            throw ex
        } finally {
            span.end()
        }
    }
}
