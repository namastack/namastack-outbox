package io.namastack.outbox

/**
 * Marks an event class for automatic outbox persistence.
 *
 * When an event annotated with @OutboxEvent is published via Spring's ApplicationEventPublisher,
 * it will be automatically persisted to the outbox database before being delivered to event listeners.
 *
 * @param key SpEL expression to extract the record key from the event. Must evaluate to a String value.
 * @param eventType Optional event type identifier.
 *
 * @author Roland Beisel
 * @since 0.3.0
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class OutboxEvent(
    val key: String,
    val eventType: String = "",
)
