package io.namastack.outbox

/**
 * Marks an event class for automatic outbox persistence.
 *
 * When an event annotated with @OutboxEvent is published via Spring's ApplicationEventPublisher,
 * it will be automatically persisted to the outbox database before (optionally) being delivered
 * to event listeners. This ensures durability of events and enables reliable event propagation.
 *
 * Recommended Usage with @DomainEvents:
 * The best practice is to use @DomainEvents in your Aggregate Root combined with @OutboxEvent
 * on your event classes. This way, Spring Data JPA automatically publishes domain events after
 * a successful transaction commit, and the OutboxEvent multicaster persists them to the outbox.
 *
 * ```
 * @Entity
 * class Customer {
 *     @DomainEvents
 *     fun domainEvents(): Collection<Any> = this.events
 *
 *     fun activate() {
 *         this.events.add(CustomerActivatedEvent(this.id))
 *     }
 * }
 *
 * @OutboxEvent(aggregateId = "customerId")
 * data class CustomerActivatedEvent(val customerId: String) : DomainEvent
 * ```
 *
 * Then publish via repository.save():
 * ```
 * @Transactional
 * fun activateCustomer(customerId: String) {
 *     val customer = repository.findById(customerId)
 *     customer.activate()
 *     repository.save(customer)  // triggers @DomainEvents -> @OutboxEvent -> outbox persistence
 * }
 * ```
 *
 * @param aggregateId SpEL expression to extract the aggregate root ID from the event.
 *                    Examples: "id", "#this.id", "order.customerId"
 *                    Must evaluate to a String value.
 *
 * @author Roland Beisel
 * @since 0.3.0
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class OutboxEvent(
    val aggregateId: String,
    val eventType: String = "",
)
