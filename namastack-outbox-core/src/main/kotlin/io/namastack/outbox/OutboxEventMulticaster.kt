package io.namastack.outbox

import io.namastack.outbox.annotation.OutboxEvent
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEvent
import org.springframework.context.PayloadApplicationEvent
import org.springframework.context.event.ApplicationEventMulticaster
import org.springframework.context.event.SimpleApplicationEventMulticaster
import org.springframework.core.ResolvableType
import org.springframework.expression.Expression
import org.springframework.expression.spel.standard.SpelExpressionParser
import org.springframework.expression.spel.support.StandardEvaluationContext
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Custom application event multicaster that intercepts @OutboxEvent annotated events.
 *
 * Provides automatic outbox persistence for Spring domain events. When an event marked
 * with @OutboxEvent is published within a transaction, it is automatically saved to the
 * outbox database and asynchronously processed by registered handlers.
 *
 * ## Key Features
 *
 * - **Automatic Detection**: Identifies events with @OutboxEvent annotation
 * - **SpEL Key Resolution**: Extracts record key via Spring Expression Language
 * - **Expression Caching**: Caches parsed SpEL expressions for performance
 * - **Configurable Publishing**: Controls whether to notify in-process listeners
 * - **Transaction Awareness**: Ensures events are saved within active transaction
 *
 * ## Processing Flow
 *
 * 1. Event published via ApplicationEventPublisher.publishEvent()
 * 2. multicastEvent() intercepts and checks for @OutboxEvent annotation
 * 3. If annotated: Extract payload and save to outbox database
 * 4. If publishAfterSave=true: Also notify in-process listeners
 * 5. If not annotated: Delegate to standard Spring event processing
 *
 * ## Expression Caching Performance
 *
 * Parsed SpEL expressions are cached in ConcurrentHashMap to avoid reparsing
 * identical expressions for every event of the same type. This is critical for
 * high-volume event processing scenarios.
 *
 * @author Roland Beisel
 * @since 0.3.0
 */
class OutboxEventMulticaster(
    private val outbox: Outbox,
    private val outboxProperties: OutboxProperties,
    private val delegateEventMulticaster: SimpleApplicationEventMulticaster,
) : ApplicationEventMulticaster by delegateEventMulticaster {
    companion object {
        private val log = LoggerFactory.getLogger(OutboxEventMulticaster::class.java)
        private val spelParser = SpelExpressionParser()
        private val expressionCache = ConcurrentHashMap<String, Expression>()
    }

    /**
     * Main entry point for event multicasting with full type information.
     *
     * Intercepts all events published through Spring's event system and routes them:
     * - @OutboxEvent marked events: Save to outbox and optionally publish to listeners
     * - Other events: Delegate to standard Spring event processing
     *
     * ## Processing Flow
     *
     * 1. Extract payload and @OutboxEvent annotation (if present)
     * 2. If no annotation found: Delegate to standard processing
     * 3. If annotation found:
     *    a. Validate active transaction (throws if missing)
     *    b. Resolve record key using SpEL expression from annotation
     *    c. Schedule event for asynchronous processing
     *    d. Optionally publish to in-process listeners (publishAfterSave)
     *
     * ## Transaction Requirement
     *
     * Must be called within an active database transaction. Spring's TransactionSynchronization
     * mechanism ensures the outbox record is persisted atomically with other business data.
     * Throws IllegalStateException if called outside transaction context.
     *
     * @param event The application event to process
     * @param eventType The resolved generic type of the event
     * @throws IllegalStateException if @OutboxEvent is present but no active transaction
     * @throws IllegalArgumentException if SpEL key expression evaluation fails
     */
    override fun multicastEvent(
        event: ApplicationEvent,
        eventType: ResolvableType?,
    ) {
        val (payload, annotation) =
            extractEventPayload(event) ?: return delegateEventMulticaster.multicastEvent(
                event,
                eventType,
            )

        val classSimpleName = payload::class.simpleName

        log.debug("Saving @OutboxEvent to outbox: $classSimpleName")
        saveOutboxRecord(payload, annotation)

        if (outboxProperties.processing.publishAfterSave) {
            log.debug("Publishing @OutboxEvent to listeners: $classSimpleName")
            delegateEventMulticaster.multicastEvent(event, eventType)
        }
    }

    /**
     * Convenience overload that automatically resolves the event type.
     *
     * Delegates to the full multicastEvent(ApplicationEvent, ResolvableType) method
     * after resolving the event type from the event instance.
     *
     * ## Usage
     *
     * Used by Spring framework internally when type information is not available
     * at the call site.
     *
     * @param event The application event to process
     */
    override fun multicastEvent(event: ApplicationEvent) {
        multicastEvent(event = event, eventType = ResolvableType.forInstance(event))
    }

    /**
     * Extracts the event payload from a Spring ApplicationEvent if marked with @OutboxEvent.
     *
     * Validates both:
     * 1. Event is a PayloadApplicationEvent (contains actual domain object)
     * 2. Payload class has @OutboxEvent annotation
     *
     * Returns the payload object and annotation as a Pair, or null if either check fails.
     *
     * ## Return Value
     *
     * - **Pair<Any, OutboxEvent>**: Event payload and its annotation (both conditions met)
     * - **null**: Event is not PayloadApplicationEvent OR payload lacks @OutboxEvent annotation
     *
     * @param event The application event to check
     * @return Pair of (payload, annotation) if @OutboxEvent present, null otherwise
     */
    private fun extractEventPayload(event: ApplicationEvent): Pair<Any, OutboxEvent>? {
        if (event !is PayloadApplicationEvent<*>) return null

        val annotation = event.payload.javaClass.getAnnotation(OutboxEvent::class.java) ?: return null

        return event.payload to annotation
    }

    /**
     * Persists an event payload to the outbox database for asynchronous processing.
     *
     * Validates active transaction and schedules the event for processing:
     * - Resolves the record key using SpEL expression from @OutboxEvent annotation
     * - Schedules event via Outbox service (atomically persisted with business transaction)
     * - Records will later be processed by registered handlers
     *
     * ## Transaction Context
     *
     * Uses Spring's TransactionSynchronizationManager to verify active transaction.
     * This ensures the outbox record is persisted atomically with business operations
     * (e.g., Order creation + OrderCreated event).
     *
     * ## Key Resolution
     *
     * Key is extracted from @OutboxEvent.key SpEL expression. If empty, defaults to null
     * (outbox will auto-generate key for load distribution).
     *
     * @param payload The event payload to persist
     * @param annotation The @OutboxEvent annotation containing configuration
     * @throws IllegalStateException if called outside an active transaction
     * @throws IllegalArgumentException if key resolution fails
     */
    private fun saveOutboxRecord(
        payload: Any,
        annotation: OutboxEvent,
    ) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw IllegalStateException("OutboxEvent requires an active transaction")
        }

        val key = resolveEventKey(payload, annotation) ?: UUID.randomUUID().toString()
        outbox.schedule(payload, key)
    }

    /**
     * Resolves the record key from the event payload using Spring Expression Language.
     *
     * Extracts a key identifier from the event payload by evaluating the SpEL expression
     * stored in @OutboxEvent.key annotation. The expression is evaluated against the
     * payload object as the root context.
     *
     * ## Expression Caching Strategy
     *
     * Parsed SpEL expressions are cached per unique expression string using ConcurrentHashMap.
     * The cache uses computeIfAbsent for thread-safe lazy initialization:
     * - First event of a type: Parses and caches the expression
     * - Subsequent events: Retrieves from cache (no reparsing overhead)
     *
     * Critical for performance in high-volume scenarios where the same event types
     * are published repeatedly.
     *
     * ## Supported SpEL Expressions
     *
     * Examples with an OrderCreatedEvent payload:
     * ```kotlin
     * @OutboxEvent(key = "id")              // Direct field: payload.id
     * @OutboxEvent(key = "#this.id")        // Explicit this: same as above
     * @OutboxEvent(key = "order.id")        // Nested access: payload.order.id
     * @OutboxEvent(key = "#root.customerId") // Root reference
     * @OutboxEvent(key = "")                // Empty: returns null (auto-generated key)
     * ```
     *
     * ## Return Value
     *
     * - **String**: Resolved key from SpEL expression
     * - **null**: Key annotation was empty (outbox will generate auto-key)
     *
     * ## Error Handling
     *
     * Throws IllegalArgumentException with helpful context if:
     * - SpEL expression syntax is invalid
     * - Expression returns null
     * - Expression returns non-String value
     * - Evaluation fails with any other exception
     *
     * @param payload The event payload object (used as SpEL root context)
     * @param annotation The @OutboxEvent annotation with key expression
     * @return Resolved string key, or null if annotation.key was empty
     * @throws IllegalArgumentException if key resolution fails
     */
    private fun resolveEventKey(
        payload: Any,
        annotation: OutboxEvent,
    ): String? {
        if (annotation.key.isEmpty()) return null

        return try {
            val spelExpression = annotation.key

            // Get cached expression or parse and cache if not present
            val expression =
                expressionCache.computeIfAbsent(spelExpression) {
                    spelParser.parseExpression(it)
                }

            // Evaluate expression with payload as root context
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
                "Failed to resolve key from SpEL: '${annotation.key}'. " +
                    "Valid examples: 'id', '#this.id', '#root.id'",
                e,
            )
        }
    }
}
