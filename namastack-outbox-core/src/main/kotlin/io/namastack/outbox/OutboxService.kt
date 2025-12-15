package io.namastack.outbox

import io.namastack.outbox.handler.OutboxHandlerRegistry
import io.namastack.outbox.handler.method.OutboxHandlerMethod
import io.namastack.outbox.interceptor.OutboxCreationInterceptorChain
import java.time.Clock
import java.util.UUID
import kotlin.reflect.KClass

/**
 * Service for scheduling outbox records with intelligent handler discovery.
 *
 * Automatically discovers all applicable handlers for a given payload and creates
 * separate records for each handler. This enables:
 * - Type hierarchy matching (handlers for superclasses also apply)
 * - Interface implementation matching (handlers for implemented interfaces)
 * - Generic fallback handlers (process any payload type)
 * - Independent retry logic per handler (if handler A fails, handler B still processes)
 *
 * ## Handler Discovery Algorithm
 *
 * For a given payload, discovers handlers in this order:
 * 1. **Exact Type Match**: Handlers registered for the payload's exact type
 * 2. **Superclass Handlers**: Handlers for parent classes (inheritance)
 * 3. **Interface Handlers**: Handlers for implemented interfaces (composition)
 * 4. **Generic Handlers**: Fallback handlers accepting Any payload type
 *
 * All discovered handlers are assigned to separate records, maintaining
 * independent processing state and retry counters per handler.
 *
 * ## Key Features
 *
 * - **Recursive Class Hierarchy**: Traverses entire class/interface tree
 * - **Infinite Recursion Prevention**: Visited-set prevents circular hierarchies
 * - **Deterministic Ordering**: LinkedHashSet maintains consistent order
 * - **Deduplication**: Handlers appearing multiple times in hierarchy registered once
 *
 * ## Example
 *
 * Given handlers:
 * ```kotlin
 * @OutboxHandler
 * fun handleOrderEvent(event: OrderEvent) { ... }  // Exact type
 *
 * @OutboxHandler
 * fun handleDomainEvent(event: DomainEvent) { ... }  // Superclass
 *
 * @OutboxHandler
 * fun handleAnyEvent(payload: Any, metadata: OutboxRecordMetadata) { ... }  // Generic
 * ```
 *
 * When scheduling `OrderCreatedEvent extends OrderEvent extends DomainEvent`:
 * - Creates 3 separate records (one per handler)
 * - Each record has independent retry state
 * - All handlers will be invoked (if enabled)
 *
 * @param handlerRegistry Registry of all discovered handler methods
 * @param outboxRecordRepository Repository for persisting records
 * @param clock Clock for timestamp generation
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
class OutboxService(
    private val creationInterceptor: OutboxCreationInterceptorChain,
    private val handlerRegistry: OutboxHandlerRegistry,
    private val outboxRecordRepository: OutboxRecordRepository,
    private val clock: Clock,
) : Outbox {
    /**
     * Schedules a payload for processing by all applicable handlers.
     *
     * ## Processing Flow
     *
     * 1. **Handler Discovery**: Finds all handlers applicable to payload type
     * 2. **Record Creation**: Creates separate record for each handler
     * 3. **Persistence**: Saves all records atomically within transaction
     *
     * Multiple records are created because:
     * - Each handler may have different retry/timeout requirements
     * - If one handler fails, others still process (decoupling)
     * - Allows per-handler monitoring and metrics
     *
     * ## Key Parameter
     *
     * - **key=null**: Auto-generates UUID (distributed across partitions)
     * - **key=value**: Groups records with same key for ordered processing
     *
     * The key is used by the scheduler to maintain strict ordering within the key
     * and for partition assignment in distributed deployments.
     *
     * @param payload The domain object to schedule (any type with handlers)
     * @param key Optional logical grouping key for ordered processing
     *
     * @throws IllegalStateException if called outside transaction context
     *                               (should not happen if OutboxEventMulticaster is used)
     *
     * Example:
     * ```kotlin
     * @Transactional
     * fun createOrder(order: Order) {
     *     orderRepository.save(order)
     *     outbox.schedule(OrderCreatedEvent(order.id), key = "order-${order.id}")
     * }
     * ```
     */
    override fun schedule(
        payload: Any,
        key: String,
    ) {
        val attributes: MutableMap<String, String> = mutableMapOf()
        creationInterceptor.applyBeforePersist(attributes)

        // Discover all applicable handlers for this payload type
        val handlerIds = collectHandlers(payload).map { it.id }.toSet()

        // Create separate record for each handler
        // Each record has independent retry/processing state
        handlerIds.forEach { handlerId ->
            val outboxRecord =
                OutboxRecord
                    .Builder<Any>()
                    .key(key)
                    .payload(payload)
                    .attributes(attributes)
                    .handlerId(handlerId)
                    .build(clock)

            outboxRecordRepository.save(outboxRecord)
        }
    }

    /**
     * Schedules a payload with auto-generated UUID key for processing.
     *
     * Convenience method for independent payloads that don't require strict ordering.
     * Automatically generates a unique UUID key internally, distributing records
     * evenly across partitions in distributed deployments.
     *
     * ## When to Use
     *
     * Use this method when event ordering is not required:
     * - Independent audit/analytics events
     * - Notifications with no order dependencies
     * - Events that don't share business logic
     *
     * For related events requiring sequential processing, use `schedule(payload, key)`
     * and provide a meaningful grouping key.
     *
     * ## Processing Behavior
     *
     * 1. Generates UUID key via `UUID.randomUUID().toString()`
     * 2. Delegates to `schedule(payload, key)` for handler discovery
     * 3. Creates separate record for each applicable handler
     * 4. Persists atomically within the current transaction
     *
     * @param payload Domain object to be processed. Handlers are discovered
     *                based on payload type (including inheritance and interfaces).
     *
     * @example
     * ```kotlin
     * @Transactional
     * fun logAuditEvent(event: AuditEvent) {
     *     auditRepository.save(event)
     *     // No ordering needed - each audit event is independent
     *     outbox.schedule(event)
     * }
     * ```
     *
     * @see schedule(payload: Any, key: String)
     */
    override fun schedule(payload: Any) {
        schedule(payload = payload, key = UUID.randomUUID().toString())
    }

    /**
     * Discovers all handlers applicable to a given payload.
     *
     * Performs recursive traversal of the payload's class hierarchy:
     * - Direct class handlers
     * - Superclass handlers (inheritance)
     * - Interface handlers (composition)
     * - Generic handlers (Any payload type)
     *
     * ## Recursion Prevention
     *
     * Uses visited-set to prevent infinite recursion through circular hierarchies.
     * Example where this matters:
     * ```
     * interface A { }
     * interface B extends A { }
     * interface C extends B { }
     * class Event implements A, B, C { }  // A appears multiple times in hierarchy
     * ```
     *
     * Without visited-set: Would process A multiple times (inefficient)
     * With visited-set: Processes each class once (correct)
     *
     * ## Handler Deduplication
     *
     * LinkedHashSet ensures:
     * - Handlers appearing multiple times (through different paths) registered once
     * - Deterministic ordering (important for reproducibility)
     * - Preserves insertion order (typed handlers before generic)
     *
     * ## Return Order
     *
     * 1. Exact type handlers
     * 2. Superclass/interface handlers (in traversal order)
     * 3. Generic handlers (registered last for fallback semantics)
     *
     * @param payload The domain object to find handlers for
     * @return List of all applicable handler methods (empty if no handlers found)
     */
    private fun collectHandlers(payload: Any): List<OutboxHandlerMethod> {
        val collected = linkedSetOf<OutboxHandlerMethod>()
        val visited = mutableSetOf<KClass<*>>()

        fun collectForClass(kclass: KClass<*>) {
            // Prevent infinite recursion through circular class hierarchies
            // visited.add() returns false if already in set (prevents reprocessing)
            if (!visited.add(kclass)) return

            // Get handlers for exact type match
            collected += handlerRegistry.getHandlersForPayloadType(kclass)

            // Recursively collect from supertypes (parent classes)
            // Example: if Event extends DomainEvent, also get handlers for DomainEvent
            kclass.supertypes.forEach { supertype ->
                (supertype.classifier as? KClass<*>)?.let { collectForClass(it) }
            }

            // Recursively collect from interfaces
            // Example: if Event implements Serializable, also get handlers for Serializable
            kclass.java.interfaces.forEach { iface ->
                collectForClass(iface.kotlin)
            }
        }

        // Start recursion from payload's actual class
        collectForClass(payload::class)

        // Add generic handlers last (fallback for any payload type)
        // These are invoked after type-specific handlers
        collected += handlerRegistry.getGenericHandlers()

        return collected.toList()
    }
}
