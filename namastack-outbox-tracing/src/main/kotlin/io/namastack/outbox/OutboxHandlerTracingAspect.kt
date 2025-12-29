package io.namastack.outbox

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode.ERROR
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect

/**
 * AOP Aspect that restores distributed tracing context when processing outbox records.
 *
 * Creates a new root span with a link to the original producer span, following
 * OpenTelemetry semantic conventions for asynchronous queue-based messaging.
 *
 * This pattern maintains trace continuity across the async boundary between
 * transaction commit and outbox processing without creating parent-child relationships.
 *
 * @param spanFactory Factory for creating consumer spans with producer links
 */
@Aspect
class OutboxHandlerTracingAspect(
    private val spanFactory: OutboxSpanFactory,
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
        val span = spanFactory.create(record) ?: return joinPoint.proceed()

        return runWithSpan(span) { joinPoint.proceed() }
    }

    /**
     * Executes block within span context with proper error handling and cleanup.
     *
     * Makes span current so nested operations inherit the trace context.
     * Records exceptions and sets error status before rethrowing.
     * Ensures span is ended even if exception occurs.
     *
     * @param span Consumer span to execute within
     * @param block Code to execute within span context
     * @return Result of block execution
     * @throws Exception Rethrows any exception after recording it in span
     */
    private inline fun <T> runWithSpan(
        span: Span,
        block: () -> T,
    ): T =
        try {
            span.makeCurrent().use { block() }
        } catch (ex: Exception) {
            span.recordException(ex)
            span.setStatus(ERROR)

            throw ex
        } finally {
            span.end()
        }
}
