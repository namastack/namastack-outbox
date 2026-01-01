package io.namastack.demo

import io.micrometer.tracing.Span
import io.micrometer.tracing.Tracer

/**
 * Executes a block within a span context, handling errors and cleanup.
 */
inline fun <T> Tracer.runWithSpan(
    span: Span,
    block: () -> T,
): T =
    try {
        withSpan(span).use { block() }
    } catch (ex: Exception) {
        span.error(ex)
        throw ex
    } finally {
        span.end()
    }
