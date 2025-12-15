package io.namastack.outbox

import io.namastack.outbox.context.OutboxContextProvider
import io.namastack.outbox.handler.OutboxHandlerRegistry
import io.namastack.outbox.handler.method.OutboxHandlerMethod
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.UUID
import kotlin.reflect.KClass

/**
 * Service for scheduling outbox records with intelligent handler discovery.
 *
 * Automatically discovers applicable handlers for a given payload and creates
 * separate records for each handler, enabling:
 * - Type hierarchy matching (handlers for superclasses also apply)
 * - Interface implementation matching
 * - Generic fallback handlers
 * - Independent retry logic per handler
 *
 * ## Handler Discovery Algorithm
 * 1. Exact Type Match - Handlers for payload's exact type
 * 2. Superclass Handlers - Handlers for parent classes
 * 3. Interface Handlers - Handlers for implemented interfaces
 * 4. Generic Handlers - Fallback handlers accepting Any
 *
 * @param handlerRegistry Registry of all discovered handler methods
 * @param outboxRecordRepository Repository for persisting records
 * @param contextProviders List of context providers for metadata collection
 * @param clock Clock for timestamp generation
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
class OutboxService(
    private val handlerRegistry: OutboxHandlerRegistry,
    private val outboxRecordRepository: OutboxRecordRepository,
    private val contextProviders: List<OutboxContextProvider>,
    private val clock: Clock,
) : Outbox {
    private val log = LoggerFactory.getLogger(OutboxService::class.java)

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
        schedule(payload, key, emptyMap())
    }

    override fun schedule(
        payload: Any,
        key: String,
        additionalContext: Map<String, String>,
    ) {
        // Collect context from providers and merge with additional context
        val context = collectContext(additionalContext)

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
                    .handlerId(handlerId)
                    .context(context)
                    .build(clock)

            outboxRecordRepository.save(outboxRecord)
        }
    }

    /**
     * Schedules a payload with auto-generated UUID key.
     *
     * Use for independent payloads that don't require ordering.
     * UUID key distributes load evenly across partitions.
     *
     * @param payload Domain object to schedule
     */
    override fun schedule(payload: Any) {
        schedule(payload, UUID.randomUUID().toString(), emptyMap())
    }

    /**
     * Schedules a payload with auto-generated UUID key and additional context.
     *
     * @param payload Domain object to schedule
     * @param additionalContext Additional context data for this record
     */
    override fun schedule(
        payload: Any,
        additionalContext: Map<String, String>,
    ) {
        schedule(payload, UUID.randomUUID().toString(), additionalContext)
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

    /**
     * Collects context from all registered context providers and merges with additional context.
     *
     * ## Merging Order
     * 1. Global [OutboxContextProvider] beans (for cross-cutting concerns)
     * 2. Additional context passed to schedule() (overrides provider context on conflicts)
     *
     * If a provider throws an exception, it is logged and skipped to ensure
     * that context collection failures don't break the scheduling process.
     *
     * @param additionalContext Additional context to merge (takes precedence)
     * @return Merged context map (empty if no providers and no additional context)
     */
    private fun collectContext(additionalContext: Map<String, String>): Map<String, String> {
        if (contextProviders.isEmpty() && additionalContext.isEmpty()) {
            return emptyMap()
        }

        // Collect from global providers
        val providerContext =
            contextProviders
                .flatMap { provider ->
                    try {
                        provider.provide().entries
                    } catch (ex: Exception) {
                        log.warn("Context provider {} failed: {}", provider::class.simpleName, ex.message, ex)
                        emptyList()
                    }
                }.associate { it.key to it.value }

        // Merge: additional context overrides provider context
        return providerContext + additionalContext
    }
}
