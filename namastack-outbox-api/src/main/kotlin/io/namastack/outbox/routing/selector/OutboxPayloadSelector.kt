package io.namastack.outbox.routing.selector

import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.routing.selector.OutboxPayloadSelector.Companion.annotation
import io.namastack.outbox.routing.selector.OutboxPayloadSelector.Companion.contextValue
import io.namastack.outbox.routing.selector.OutboxPayloadSelector.Companion.predicate
import io.namastack.outbox.routing.selector.OutboxPayloadSelector.Companion.type
import java.util.function.BiPredicate

/**
 * Selector for matching outbox payloads to routing rules.
 *
 * Used by externalization modules (Kafka, RabbitMQ, SNS, etc.) to determine
 * which payloads match specific routing configurations.
 *
 * Rules are evaluated in declaration order - the first matching rule wins.
 *
 * Use the companion object factory methods to create selectors:
 * - [type] - Match by payload class (supports inheritance)
 * - [predicate] - Match by custom predicate
 * - [annotation] - Match by annotation on payload class
 * - [contextValue] - Match by metadata context value
 *
 * ## Example (Kotlin)
 *
 * ```kotlin
 * outboxRouting {
 *     // First match wins - order matters!
 *     route(OutboxPayloadSelector.type(OrderPlacedEvent::class.java)) {
 *         target("order-placed")
 *     }
 *     route(OutboxPayloadSelector.type(OrderEvent::class.java)) {
 *         target("orders")  // Fallback for other OrderEvent subtypes
 *     }
 *     route(OutboxPayloadSelector.predicate { _, metadata ->
 *         metadata.context["priority"] == "high"
 *     }) {
 *         target("high-priority")
 *     }
 *     defaults {
 *         target("domain-events")
 *     }
 * }
 * ```
 *
 * ## Example (Java)
 *
 * ```java
 * OutboxRouting.builder()
 *     .route(OutboxPayloadSelector.type(OrderPlacedEvent.class), route -> route
 *         .target("order-placed")
 *     )
 *     .route(OutboxPayloadSelector.type(OrderEvent.class), route -> route
 *         .target("orders")
 *     )
 *     .defaults(route -> route
 *         .target("domain-events")
 *     )
 *     .build();
 * ```
 *
 * @author Roland Beisel
 * @since 1.1.0
 */
sealed interface OutboxPayloadSelector {
    /**
     * Tests if this selector matches the given payload and metadata.
     *
     * @param payload The outbox payload object
     * @param metadata The outbox record metadata
     * @return true if this selector matches, false otherwise
     */
    fun matches(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): Boolean

    companion object {
        /**
         * Creates a selector that matches payloads by type (including subtypes).
         *
         * @param T The payload type
         * @param type The payload class to match
         * @return A type-based selector
         */
        @JvmStatic
        fun <T : Any> type(type: Class<T>): OutboxPayloadSelector = TypeSelector(type)

        /**
         * Creates a selector that matches payloads by a custom predicate.
         *
         * @param predicate The predicate to test payloads
         * @return A predicate-based selector
         */
        @JvmStatic
        fun predicate(predicate: (Any, OutboxRecordMetadata) -> Boolean): OutboxPayloadSelector =
            PredicateSelector { p, m -> predicate(p, m) }

        /**
         * Creates a selector that matches payloads by a custom predicate (Java-friendly).
         *
         * @param predicate The predicate to test payloads
         * @return A predicate-based selector
         */
        @JvmStatic
        fun predicate(predicate: BiPredicate<Any, OutboxRecordMetadata>): OutboxPayloadSelector =
            PredicateSelector(predicate)

        /**
         * Creates a selector that matches payloads annotated with the given annotation.
         *
         * Uses Spring's AnnotationUtils.findAnnotation which supports:
         * - Direct annotations on the class
         * - Meta-annotations (annotations on annotations)
         * - Inherited annotations from superclasses
         *
         * @param A The annotation type
         * @param annotationType The annotation class to match
         * @return An annotation-based selector
         */
        @JvmStatic
        fun <A : Annotation> annotation(annotationType: Class<A>): OutboxPayloadSelector =
            AnnotationSelector(annotationType)

        /**
         * Creates a selector that matches by a specific context value in metadata.
         *
         * @param contextKey The context key to check
         * @param expectedValue The expected value
         * @return A context-based selector
         */
        @JvmStatic
        fun contextValue(
            contextKey: String,
            expectedValue: String,
        ): OutboxPayloadSelector = ContextValueSelector(contextKey, expectedValue)
    }
}
