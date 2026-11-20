package io.namastack.outbox.multicaster

import io.namastack.outbox.Outbox
import io.namastack.outbox.mapping.EventMapper
import io.namastack.outbox.runtime.OutboxRuntimeSettings
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.context.ApplicationEvent
import org.springframework.context.PayloadApplicationEvent
import org.springframework.context.event.ApplicationEventMulticaster
import org.springframework.context.event.SimpleApplicationEventMulticaster
import org.springframework.core.ResolvableType

/**
 * Custom application event multicaster that intercepts @OutboxEvent annotated events.
 *
 * Provides automatic outbox persistence for Spring application events. When an event marked
 * with @OutboxEvent is published within a transaction, it is automatically saved to the
 * outbox database and asynchronously processed by registered handlers.
 * Registered [EventMapper] beans translate events at publication time, before outbox scheduling. Mapped
 * targets follow the same annotation-based persistence rules and are never mapped recursively;
 * original payloads remain available to local listeners.
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
 * 1. Unwrap a published payload event or inspect the native application event itself
 * 2. Apply an exact or unique most-specific mapper once, if one is registered
 * 3. Resolve annotation, key, and context against the original or mapped target payload
 * 4. Persist annotated targets and notify local listeners only when publishAfterSave=true;
 *    publish unannotated targets normally
 * 5. After successful handling of a mapped target, also publish the original event locally
 *
 * @param outboxProvider Provider for transaction-aware outbox scheduling
 * @param settings Multicaster persistence and local publishing settings
 * @param delegateEventMulticaster Delegate for ordinary local Spring event delivery
 * @param eventMappers Provider for initialized mapper beans, supported since 2.1.0
 * @author Roland Beisel
 * @since 0.3.0
 */
class OutboxEventMulticaster(
    private val outboxProvider: ObjectProvider<Outbox>,
    private val settings: OutboxRuntimeSettings.Multicaster,
    private val delegateEventMulticaster: SimpleApplicationEventMulticaster,
    private val eventMappers: ObjectProvider<EventMapper<*, *>>,
) : ApplicationEventMulticaster by delegateEventMulticaster,
    SmartInitializingSingleton {
    private val mapperRegistry by lazy { EventMapperRegistry(eventMappers.orderedStream().toList()) }

    companion object {
        private val log = LoggerFactory.getLogger(OutboxEventMulticaster::class.java)
    }

    /**
     * Validates mapper registrations after Spring has initialized the singleton beans.
     *
     * @throws IllegalArgumentException if a mapper source is annotated or registered more than once
     * @throws IllegalStateException if a mapper's generic source type cannot be resolved
     * @since 2.1.0
     */
    override fun afterSingletonsInstantiated() {
        mapperRegistry
    }

    /**
     * Main entry point for event multicasting with full type information.
     *
     * Applies an exact or unique most-specific registered mapper to published events. Targets go
     * directly through delivery without another mapping round; the source remains available to
     * local listeners. Events without a mapper retain their annotation-based delivery behavior.
     *
     * ## Transaction Requirement
     *
     * An original or mapped @OutboxEvent requires an active transaction managing the outbox
     * and business data. Mapping to an ordinary Spring event requires no transaction.
     *
     * @param event The application event to process
     * @param eventType The resolved generic type of the event
     * @throws IllegalStateException if @OutboxEvent is present but no active transaction
     * or incomparable mapper source types match
     * @throws IllegalArgumentException if a mapped source declares @OutboxEvent, mapping returns null,
     * or a key/context expression cannot be resolved
     */
    override fun multicastEvent(
        event: ApplicationEvent,
        eventType: ResolvableType?,
    ) {
        val mappedEvent = mapEventIfSupported(event)
        if (mappedEvent == null) {
            scheduleOrPublishEvent(event, eventType)
            return
        }

        // Bypass mapping so the target cannot start another mapping round.
        scheduleOrPublishEvent(mappedEvent, ResolvableType.forInstance(mappedEvent))

        // Keep the original available to local listeners without scheduling it in the outbox.
        delegateEventMulticaster.multicastEvent(event, eventType)
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
     * Maps a published event and adapts the result for Spring event processing.
     *
     * Payload events are unwrapped before mapping; native application events are mapped directly.
     * Existing application event targets are retained. Ordinary mapped payloads are wrapped with the
     * original event source. This method neither schedules nor publishes the result.
     *
     * @param event Published Spring event or payload wrapper to inspect for a registered mapper
     * @return Mapped Spring event, or `null` when no mapper applies
     * @throws IllegalStateException if incomparable mapper source types match
     * @throws IllegalArgumentException if the source is annotated or mapping returns null
     * @since 2.1.0
     */
    private fun mapEventIfSupported(event: ApplicationEvent): ApplicationEvent? {
        val sourcePayload = if (event is PayloadApplicationEvent<*>) event.payload else event
        val mappedPayload = mapperRegistry.mapIfSupported(sourcePayload) ?: return null

        return mappedPayload as? ApplicationEvent ?: PayloadApplicationEvent(event.source, mappedPayload)
    }

    /**
     * Schedules an annotated event or publishes an unannotated event to local Spring listeners.
     *
     * Key and context expressions are resolved against this delivery payload, not the mapper's
     * original source. Annotated payloads reach local listeners only when publishAfterSave is enabled.
     *
     * @param event Original or mapped event to schedule or publish without applying mappers
     * @param eventType Resolved event type used for local listener selection
     * @throws IllegalStateException if outbox scheduling requires a missing transaction or outbox bean
     * @throws IllegalArgumentException if a key/context expression cannot be resolved
     */
    private fun scheduleOrPublishEvent(
        event: ApplicationEvent,
        eventType: ResolvableType?,
    ) {
        val resolvedEvent = OutboxEventResolver.resolve(event)
        if (resolvedEvent == null) {
            delegateEventMulticaster.multicastEvent(event, eventType)
            return
        }

        val classSimpleName = resolvedEvent.payload::class.simpleName

        log.debug("Saving @OutboxEvent to outbox: $classSimpleName")
        saveOutboxRecord(resolvedEvent)

        if (settings.publishAfterSave) {
            log.debug("Publishing @OutboxEvent to listeners: $classSimpleName")
            delegateEventMulticaster.multicastEvent(event, eventType)
        }
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
