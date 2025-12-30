package io.namastack.outbox

import io.micrometer.tracing.Span
import io.micrometer.tracing.Tracer
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect

/**
 * AOP Aspect that restores distributed tracing context when processing outbox records.
 *
 * Creates a child span from the original producer span, following
 * OpenTelemetry semantic conventions for asynchronous queue-based messaging.
 *
 * This pattern maintains trace continuity across the async boundary between
 * transaction commit and outbox processing, allowing distributed traces to span
 * the entire lifecycle from record creation to final processing.
 *
 * @param spanFactory Factory for creating consumer spans with producer links
 * @param tracer Micrometer tracer for managing span lifecycle and context propagation
 */
@Aspect
class OutboxHandlerTracingAspect(
    private val spanFactory: OutboxSpanFactory,
    private val tracer: Tracer,
) {
    /**
     * Intercepts outbox record processing and wraps execution in a tracing span.
     *
     * Pointcut matches:
     * - Bean name: outboxRecordProcessorChain (entry point of processor chain)
     * - Method: handle(OutboxRecord)
     *
     * Creates a consumer span with link to producer if valid trace context exists
     * in the record. Proceeds without span if no context found (graceful degradation).
     *
     * @param joinPoint Intercepted processor chain invocation
     * @param record Outbox record being processed
     * @return Result of processor chain execution
     */
    @Around("bean(outboxRecordProcessorChain) && execution(* handle(..)) && args(record)")
    fun traceRecordProcessing(
        joinPoint: ProceedingJoinPoint,
        record: OutboxRecord<*>,
    ): Any? {
        val spanBuilder = spanFactory.create(record) ?: return joinPoint.proceed()

        return runWithSpan(spanBuilder, record) { joinPoint.proceed() }
    }

    /**
     * Executes a block within a span context with proper error handling and cleanup.
     *
     * Makes the span current so nested operations inherit the trace context.
     * If the outbox record has a failure exception, it is recorded in the span
     * before the span is ended. Ensures the span is always properly closed
     * even if an exception occurs during execution.
     *
     * @param T The return type of the block
     * @param spanBuilder Span builder to create and execute within
     * @param record Outbox record being processed, used to check for failure exceptions
     * @param block Lambda to execute within the span context
     * @return Result of block execution
     */
    private inline fun <T> runWithSpan(
        spanBuilder: Span.Builder,
        record: OutboxRecord<*>,
        block: () -> T,
    ): T {
        val span = spanBuilder.start()
        try {
            return tracer.withSpan(span).use { block() }
        } finally {
            record.failureException?.let { error -> span.error(error) }
            span.end()
        }
    }
}
