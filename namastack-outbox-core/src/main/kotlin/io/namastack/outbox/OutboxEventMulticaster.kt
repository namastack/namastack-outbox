package io.namastack.outbox

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.ApplicationEvent
import org.springframework.context.event.ApplicationEventMulticaster
import org.springframework.context.event.SimpleApplicationEventMulticaster
import org.springframework.core.ResolvableType

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
    private val outboxProvider: ObjectProvider<Outbox>,
    private val outboxProperties: OutboxProperties,
    private val delegateEventMulticaster: SimpleApplicationEventMulticaster,
) : ApplicationEventMulticaster by delegateEventMulticaster {
    companion object {
        private val log = LoggerFactory.getLogger(OutboxEventMulticaster::class.java)
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
        val resolvedEvent =
            OutboxEventResolver.resolve(event) ?: return delegateEventMulticaster.multicastEvent(
                event,
                eventType,
            )

        val classSimpleName = resolvedEvent.payload::class.simpleName

        log.debug("Saving @OutboxEvent to outbox: $classSimpleName")
        saveOutboxRecord(resolvedEvent)

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
     * Schedules one resolved event with the configured outbox.
     *
     * @param event Resolved payload, key, and event-specific context
     * @throws IllegalStateException if no outbox is available
     */
    private fun saveOutboxRecord(event: ResolvedOutboxEvent) {
        val outbox =
            outboxProvider.getIfAvailable()
                ?: throw IllegalStateException("No Outbox bean available to schedule @OutboxEvent")

        outbox.schedule(event.payload, event.key, event.context)
    }
}
