package io.namastack.outbox

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.BeanFactory
import org.springframework.context.ApplicationEvent
import org.springframework.context.PayloadApplicationEvent
import org.springframework.context.event.SimpleApplicationEventMulticaster
import org.springframework.core.ResolvableType
import org.springframework.expression.Expression
import org.springframework.expression.spel.standard.SpelExpressionParser
import org.springframework.expression.spel.support.StandardEvaluationContext
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/**
 * Custom application event multicaster that intercepts @OutboxEvent annotated events.
 *
 * This class extends Spring's SimpleApplicationEventMulticaster to provide automatic
 * outbox persistence for domain events. When an event marked with @OutboxEvent is
 * published, it is automatically saved to the outbox database.
 *
 * Key Features:
 * - Automatic detection of @OutboxEvent annotations
 * - SpEL-based aggregateId extraction from event properties
 * - Expression caching for performance optimization
 * - Configurable publishing behavior (publishAfterSave flag)
 *
 * SpEL Expression Caching:
 * Parsed SpEL expressions are cached in a ConcurrentHashMap to avoid re-parsing
 * the same expressions for every event. This significantly improves performance
 * for high-volume event processing.
 *
 * @author Roland Beisel
 * @since 0.3.0
 */
class OutboxEventMulticaster(
    beanFactory: BeanFactory,
    private val baseMulticaster: SimpleApplicationEventMulticaster = SimpleApplicationEventMulticaster(beanFactory),
    private val outboxRecordRepository: OutboxRecordRepository,
    private val outboxEventSerializer: OutboxEventSerializer,
    private val outboxProperties: OutboxProperties,
    private val clock: Clock,
) : SimpleApplicationEventMulticaster(beanFactory) {
    companion object {
        private val log = LoggerFactory.getLogger(OutboxEventMulticaster::class.java)
        private val spelParser = SpelExpressionParser()
        private val expressionCache = ConcurrentHashMap<String, Expression>()
    }

    /**
     * Intercepts and processes application events.
     *
     * Processing flow:
     * 1. Extracts payload if event is marked with @OutboxEvent
     * 2. Saves event to outbox database (synchronized with transaction)
     * 3. Optionally publishes to listeners (controlled by publishAfterSave config)
     * 4. Handles non-outbox events normally
     *
     * @param event The application event to process
     * @param eventType The resolved event type
     */
    override fun multicastEvent(
        event: ApplicationEvent,
        eventType: ResolvableType?,
    ) {
        val (payload, annotation) =
            extractEventPayload(event) ?: return baseMulticaster.multicastEvent(
                event,
                eventType,
            )

        log.debug("Saving @OutboxEvent to outbox: ${payload::class.simpleName}")
        saveOutboxRecord(payload, annotation)

        if (outboxProperties.processing.publishAfterSave) {
            log.debug("Publishing @OutboxEvent to listeners: ${payload::class.simpleName}")
            baseMulticaster.multicastEvent(event, eventType)
        }
    }

    /**
     * Convenience override that resolves the event type automatically.
     *
     * Delegates to the full multicastEvent(ApplicationEvent, ResolvableType) method
     * with the event type resolved from the event instance.
     *
     * @param event The application event to process
     */
    override fun multicastEvent(event: ApplicationEvent) {
        multicastEvent(event = event, eventType = ResolvableType.forInstance(event))
    }

    /**
     * Extracts the event payload from a Spring ApplicationEvent if it's marked with @OutboxEvent.
     *
     * Checks if the event is a PayloadApplicationEvent and if its payload class
     * has the @OutboxEvent annotation. Returns the payload only if both conditions are met.
     *
     * @param event The application event to check
     * @return The payload object if @OutboxEvent annotation is present, null otherwise
     */
    private fun extractEventPayload(event: ApplicationEvent): Pair<Any, OutboxEvent>? {
        if (event !is PayloadApplicationEvent<*>) return null

        val annotation = event.payload.javaClass.getAnnotation(OutboxEvent::class.java) ?: return null

        return event.payload to annotation
    }

    /**
     * Persists an event payload to the outbox database.
     *
     * Creates an OutboxRecord from the event, extracting:
     * - aggregateId: Extracted via SpEL expression from event payload
     * - eventType: Simple class name of the event
     * - payload: Serialized event object (via OutboxEventSerializer)
     * - timestamp: Current clock time
     *
     * @param payload The event payload to persist
     */
    private fun saveOutboxRecord(
        payload: Any,
        annotation: OutboxEvent,
    ) {
        val aggregateId = resolveAggregateId(payload, annotation)

        outboxRecordRepository.save(
            record =
                OutboxRecord
                    .Builder()
                    .aggregateId(aggregateId)
                    .eventType(payload::class.simpleName!!)
                    .payload(outboxEventSerializer.serialize(payload))
                    .build(clock),
        )
    }

    /**
     * Resolves the aggregateId from the event payload using SpEL expression.
     *
     * The aggregateId is extracted from the @OutboxEvent annotation's aggregateId parameter,
     * which contains a SpEL expression. The expression is evaluated against the event payload
     * to extract the actual aggregate ID value.
     *
     * Expression caching:
     * Parsed SpEL expressions are cached to avoid re-parsing the same expression
     * for every event of the same type. The cache uses computeIfAbsent for thread-safe
     * lazy initialization.
     *
     * Supported SpEL expressions:
     * - "id" - direct field access
     * - "#this.id" - explicit this reference
     * - "#root.customerId" - explicit root reference
     * - "order.id" - nested property access
     *
     * @param payload The event payload object
     * @return The resolved aggregateId as a String
     * @throws IllegalStateException if @OutboxEvent annotation is not found
     * @throws IllegalArgumentException if SpEL expression evaluation fails or returns non-String
     */
    private fun resolveAggregateId(
        payload: Any,
        annotation: OutboxEvent,
    ): String =
        try {
            val spelExpression = annotation.aggregateId

            val expression =
                expressionCache.computeIfAbsent(spelExpression) {
                    spelParser.parseExpression(it)
                }

            val context = StandardEvaluationContext(payload)

            when (val result = expression.getValue(context)) {
                null -> throw IllegalArgumentException("SpEL expression returned null: '$spelExpression'")

                !is String -> throw IllegalArgumentException(
                    "SpEL expression must return String, but got ${result::class.simpleName}: '$spelExpression'",
                )

                else -> result
            }
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "Failed to resolve aggregateId from SpEL: '${annotation.aggregateId}'. " +
                    "Valid examples: 'id', '#this.id', '#root.id'",
                e,
            )
        }
}
