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
 * - **SpEL Context Resolution**: Extracts record context via Spring Expression Language
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

        if (outboxProperties.processing.publishAfterSave ?: outboxProperties.multicaster.publishAfterSave) {
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
     * - Resolves the record key using SpEL expression from @OutboxEvent.key annotation
     * - Resolves event-specific context from @OutboxEvent.context entries
     * - Schedules event via Outbox service (atomically persisted with business transaction)
     * - Context is merged with global context from OutboxContextProvider beans
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
     * (outbox will auto-generate UUID key for load distribution).
     *
     * ## Context Handling
     *
     * Event-specific context from annotation is passed as `additionalContext` to the outbox service.
     * It will be merged with global context from OutboxContextProvider beans:
     * - Global context (from providers): Applied to all records
     * - Additional context (from annotation): Event-specific, takes precedence on key conflicts
     *
     * @param payload The event payload to persist
     * @param annotation The @OutboxEvent annotation containing configuration
     * @throws IllegalStateException if called outside an active transaction
     * @throws IllegalArgumentException if key or context resolution fails
     */
    private fun saveOutboxRecord(
        payload: Any,
        annotation: OutboxEvent,
    ) {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "OutboxEvent requires an active transaction"
        }

        val key = resolveEventKey(payload, annotation) ?: UUID.randomUUID().toString()
        val context = resolveContext(payload, annotation) ?: emptyMap()
        outbox.schedule(payload, key, context)
    }

    /**
     * Resolves the record key from the event payload using Spring Expression Language.
     *
     * Extracts a key identifier from the event payload by evaluating the SpEL expression
     * stored in @OutboxEvent.key annotation. The expression is evaluated against the
     * payload object as the root context.
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

        return resolveValue(payload, annotation.key)
    }

    /**
     * Resolves event-specific context from the @OutboxEvent annotation using SpEL.
     *
     * Extracts context metadata from the event payload by evaluating SpEL expressions
     * defined in @OutboxEvent.context entries. Each entry's value is evaluated against
     * the payload object as the root context.
     *
     * ## Expression Resolution
     *
     * For each OutboxContextEntry:
     * - Key: Used as-is (literal string, not evaluated)
     * - Value: Evaluated as SpEL expression against the payload
     *
     * ## Supported SpEL Expressions
     *
     * Examples with an OrderConfirmedEvent payload:
     * ```kotlin
     * @OutboxEvent(
     *     key = "#this.orderId",
     *     context = [
     *         OutboxContextEntry(key = "customerId", value = "#this.customerId"),        // Direct field
     *         OutboxContextEntry(key = "orderTotal", value = "#this.total.toString()"),  // Method call
     *         OutboxContextEntry(key = "region", value = "#this.address.region"),        // Nested property
     *         OutboxContextEntry(key = "eventType", value = "'ORDER_CONFIRMED'"),          // Static value
     *         OutboxContextEntry(key = "itemCount", value = "#this.items.size().toString()") // Collection size
     *     ]
     * )
     * ```
     *
     * ## Return Value
     *
     * - **Map<String, String>**: Resolved context entries (key -> evaluated value)
     * - **null**: No context entries defined in annotation (empty array)
     *
     * ## Error Handling
     *
     * Throws IllegalArgumentException if any context entry value:
     * - Has invalid SpEL syntax
     * - Evaluates to null
     * - Evaluates to non-String type (must explicitly convert with .toString())
     *
     * @param payload The event payload object (used as SpEL root context)
     * @param annotation The @OutboxEvent annotation with context entries
     * @return Map of resolved context entries, or null if no entries defined
     * @throws IllegalArgumentException if context resolution fails
     */
    private fun resolveContext(
        payload: Any,
        annotation: OutboxEvent,
    ): Map<String, String>? {
        if (annotation.context.isEmpty()) return null

        return annotation.context
            .associate { entry ->
                val resolveValue = resolveValue(payload, entry.value)
                entry.key to resolveValue
            }
    }

    /**
     * Resolves a SpEL expression to a String value using the event payload as context.
     *
     * This is a shared utility method used by both key and context resolution.
     * It evaluates SpEL expressions against the payload object and ensures the
     * result is a String.
     *
     * ## Expression Caching
     *
     * Parsed SpEL expressions are cached to avoid reparsing on every invocation.
     * This is critical for performance when the same event types are published repeatedly.
     *
     * ## Validation
     *
     * The resolved value must be:
     * - Non-null (expression cannot return null)
     * - String type (non-String values must be explicitly converted with .toString())
     *
     * @param payload The event payload object (used as SpEL root context)
     * @param value The SpEL expression to evaluate
     * @return The resolved String value
     * @throws IllegalArgumentException if expression is invalid, returns null, or returns non-String
     */
    private fun resolveValue(
        payload: Any,
        value: String,
    ): String {
        try {
            // Get cached expression or parse and cache if not present
            val expression =
                expressionCache.computeIfAbsent(value) {
                    spelParser.parseExpression(it)
                }

            // Evaluate expression with payload as root context
            val context = StandardEvaluationContext(payload)

            when (val result = expression.getValue(context)) {
                null -> throw IllegalArgumentException("SpEL expression returned null: '$value'")

                !is String -> throw IllegalArgumentException(
                    "SpEL expression must return String, but got ${result::class.simpleName}: '$value'",
                )

                else -> return result
            }
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "Failed to resolve value from SpEL: '$value'. " +
                    "Valid examples: 'id', '#this.id', '#root.id'",
                e,
            )
        }
    }
}
