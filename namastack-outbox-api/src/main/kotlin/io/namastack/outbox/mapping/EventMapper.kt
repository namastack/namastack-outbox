package io.namastack.outbox.mapping

import io.namastack.outbox.annotation.OutboxEvent

/**
 * Maps a Spring application event payload at publication time.
 *
 * Plain objects published through Spring are unwrapped from their payload event. Native
 * application event instances are passed to the mapper directly.
 * Broad source types such as `Any` also match Spring lifecycle events; prefer specific source types.
 *
 * For targets annotated with [OutboxEvent], mapping completes before the target is serialized
 * and stored in the outbox. Outbox handlers and retries process the stored target payload;
 * they do not run the mapper again. Unannotated targets are published to local Spring listeners.
 *
 * Implementations must be registered as Spring beans, for example through `@Component` or `@Bean`.
 * Mapping requires Namastack's application-event multicaster to be enabled.
 * Namastack collects these beans; it does not scan for or instantiate other implementations.
 * Mapping becomes available after singleton initialization and mapper discovery complete. Events
 * published earlier, including from constructors or `@PostConstruct`, retain ordinary Spring/outbox
 * handling without mapping and are not replayed. Publish events that require mapping after startup.
 * The source type is resolved from [S] through the bean's complete type hierarchy, including
 * generic base classes and interfaces behind Spring AOP proxies, as for typed outbox handlers.
 * Declare a resolvable source type on the implementation rather than an erased type variable.
 *
 * An exact runtime type match takes precedence; otherwise the unique most-specific assignable
 * source type is selected, including abstract classes and interfaces. Incomparable matching types
 * cause an error when the event is published. Mapping runs synchronously and must return one non-null payload.
 * The source must not declare [OutboxEvent]. An annotated target is scheduled in the current transaction;
 * other targets use normal Spring event delivery. Targets are not mapped again. The original event
 * remains available to local listeners. Direct `Outbox.schedule` calls do not invoke mappers.
 *
 * Example:
 * ```kotlin
 * @Component
 * class OrderPaidMapper : EventMapper<OrderPaid, OrderPaidMessage> {
 *     override fun map(event: OrderPaid): OrderPaidMessage =
 *         OrderPaidMessage(orderId = event.orderId.value)
 * }
 * ```
 *
 * @param S Source event payload type
 * @param T Target event payload type
 * @author Roland Beisel
 * @since 2.1.0
 */
interface EventMapper<S : Any, T : Any> {
    /**
     * Maps one published source event to its target event within the publisher's calling thread.
     *
     * A target annotated with [OutboxEvent] requires an active transaction managing both the
     * business data and outbox. Mapping and scheduling failures propagate to the publisher,
     * allowing that transaction to roll back. Other target types need no transaction.
     *
     * @param event Source payload of type [S]
     * @return One non-null target payload of type [T]
     * @throws Exception to abort publication when mapping fails
     */
    fun map(event: S): T
}
