package io.namastack.outbox

import io.namastack.outbox.context.OutboxContextCollector
import io.namastack.outbox.handler.OutboxHandlerRegistry
import io.namastack.outbox.handler.method.OutboxHandlerMethod
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
    private val contextCollector: OutboxContextCollector,
    private val handlerRegistry: OutboxHandlerRegistry,
    private val outboxRecordRepository: OutboxRecordRepository,
    private val clock: Clock,
) : Outbox {
    /**
     * Schedules a record with an explicit key and additional context for processing.
     *
     * This is the most flexible scheduling method, allowing full control over key and context metadata.
     * It discovers all applicable handlers and creates separate outbox records for each handler with
     * merged context from both global providers and the additional context parameter.
     *
     * ## Context Handling
     *
     * The final context stored with the outbox record is a merge of:
     * 1. **Global context** from all [io.namastack.outbox.context.OutboxContextProvider] beans
     * 2. **Additional context** passed via the `additionalContext` parameter
     *
     * If the same key appears in both sources, the additional context value takes precedence.
     *
     * ## Processing Behavior
     *
     * 1. **Context Collection**: Collects global context from all registered providers
     * 2. **Context Merging**: Merges global context with additional context
     * 3. **Handler Discovery**: Finds all handlers registered for the payload type
     * 4. **Record Creation**: Creates separate record for each handler with merged context
     * 5. **Persistence**: Saves atomically within the current transaction
     *
     * ## Handler Discovery
     *
     * Multiple records are created because:
     * - Each handler may have different retry/timeout requirements
     * - If one handler fails, others still process (decoupling)
     * - Allows per-handler monitoring and metrics
     *
     * ## Transaction Context
     *
     * This method must be called within an active database transaction (@Transactional).
     * The record is persisted atomically with business data.
     *
     * @param payload Domain object to be processed. Type determines which handlers are invoked.
     * @param key Logical grouping key for ordered processing. Related events should
     *            use the same key to ensure sequential processing.
     * @param additionalContext Event-specific context metadata to be stored with the record.
     *                          This is merged with global context from OutboxContextProvider beans.
     *
     * @throws IllegalStateException if called outside a transaction context
     *
     * @example
     * ```kotlin
     * @Transactional
     * fun confirmOrder(order: Order) {
     *     order.status = OrderStatus.CONFIRMED
     *     orderRepository.save(order)
     *
     *     outbox.schedule(
     *         payload = OrderConfirmedEvent(order.id, order.totalAmount),
     *         key = "order-${order.id}",
     *         additionalContext = mapOf(
     *             "customerId" to order.customerId,
     *             "orderTotal" to order.totalAmount.toString(),
     *             "itemCount" to order.items.size.toString()
     *         )
     *     )
     * }
     * ```
     *
     * @example With global tracing context
     * ```kotlin
     * // Global provider (automatically applied to all records)
     * @Component
     * class TracingContextProvider : OutboxContextProvider {
     *     override fun provide() = mapOf(
     *         "traceId" to MDC.get("traceId"),
     *         "spanId" to MDC.get("spanId")
     *     )
     * }
     *
     * // Usage - tracing context is automatically included
     * @Transactional
     * fun processPayment(payment: Payment) {
     *     paymentRepository.save(payment)
     *     outbox.schedule(
     *         payload = PaymentProcessedEvent(payment.id),
     *         key = "payment-${payment.id}",
     *         additionalContext = mapOf(
     *             "amount" to payment.amount.toString(),
     *             "currency" to payment.currency
     *         )
     *     )
     *     // Final context: { traceId, spanId, amount, currency }
     * }
     * ```
     */
    override fun schedule(
        payload: Any,
        key: String,
        additionalContext: Map<String, String>,
    ) {
        // Collect context from providers and merge with additional context
        val context = contextCollector.collectContext() + additionalContext

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
                    .context(context)
                    .handlerId(handlerId)
                    .build(clock)

            outboxRecordRepository.save(outboxRecord)
        }
    }

    /**
     * Schedules a record with an explicit key for processing.
     *
     * Records with the same key are processed sequentially by registered handlers,
     * ensuring strict ordering for related events. The key also determines partition
     * assignment in distributed environments.
     *
     * ## Context Handling
     *
     * This method uses **only global context** from [io.namastack.outbox.context.OutboxContextProvider] beans.
     * If you need to add event-specific context, use `schedule(payload, key, additionalContext)` instead.
     *
     * ## Processing Behavior
     *
     * 1. **Context Collection**: Collects global context from all registered providers
     * 2. **Handler Discovery**: Finds all handlers registered for the payload type
     * 3. **Record Creation**: Creates separate record for each handler with global context
     * 4. **Persistence**: Saves atomically within the current transaction
     *
     * ## Transaction Context
     *
     * This method must be called within an active database transaction (@Transactional).
     * The record is persisted atomically with business data, ensuring no loss even
     * if the transaction rolls back.
     *
     * ## Key Semantics
     *
     * Records with the same key are processed sequentially (e.g., "order-123" ensures
     * all order events are processed in strict order). Use a meaningful key to group
     * related events for ordered processing.
     *
     * @param payload Domain object to be processed. Type determines which handlers
     *                are invoked (inheritance and interfaces supported).
     * @param key Logical grouping key for ordered processing. Related events should
     *            use the same key to ensure sequential processing.
     *
     * @throws IllegalStateException if called outside a transaction context
     *
     * @example
     * ```kotlin
     * @Transactional
     * fun createOrder(order: Order) {
     *     orderRepository.save(order)
     *     // Group all order events by order ID for sequential processing
     *     outbox.schedule(OrderCreatedEvent(order.id), key = "order-${order.id}")
     * }
     * ```
     *
     * @example With global context providers
     * ```kotlin
     * // Global provider (automatically applied)
     * @Component
     * class TracingContextProvider : OutboxContextProvider {
     *     override fun provide() = mapOf("traceId" to MDC.get("traceId"))
     * }
     *
     * @Transactional
     * fun shipOrder(order: Order) {
     *     order.status = OrderStatus.SHIPPED
     *     orderRepository.save(order)
     *     // Record includes traceId from global provider
     *     outbox.schedule(OrderShippedEvent(order.id), key = "order-${order.id}")
     * }
     * ```
     *
     * @see schedule(payload: Any, key: String, additionalContext: Map<String, String>)
     */
    override fun schedule(
        payload: Any,
        key: String,
    ) {
        schedule(
            payload = payload,
            key = key,
            additionalContext = emptyMap(),
        )
    }

    /**
     * Schedules a record with auto-generated UUID key and additional context for processing.
     *
     * Convenience method for independent payloads that don't require strict ordering but need
     * event-specific context metadata. Generates a unique UUID key internally, distributing
     * load evenly across partitions in distributed deployments.
     *
     * ## Context Handling
     *
     * The final context stored with the outbox record is a merge of:
     * 1. **Global context** from all [io.namastack.outbox.context.OutboxContextProvider] beans
     * 2. **Additional context** passed via the `additionalContext` parameter
     *
     * If the same key appears in both sources, the additional context value takes precedence.
     *
     * ## When to Use
     *
     * Use this method when:
     * - Event ordering is not required (independent events)
     * - You need to attach event-specific context metadata
     * - Examples: audit events, notifications, analytics events with metadata
     *
     * For ordered processing, use `schedule(payload, key, additionalContext)` with a meaningful key.
     *
     * ## Processing Behavior
     *
     * 1. Generates UUID key via `UUID.randomUUID().toString()`
     * 2. Collects global context from all registered providers
     * 3. Merges global context with additional context
     * 4. Creates separate record for each applicable handler
     * 5. Persists atomically within the current transaction
     *
     * @param payload Domain object to be processed. Handlers are discovered
     *                based on payload type (including inheritance and interfaces).
     * @param additionalContext Event-specific context metadata to be stored with the record.
     *                          This is merged with global context from OutboxContextProvider beans.
     *
     * @throws IllegalStateException if called outside a transaction context
     *
     * @example
     * ```kotlin
     * @Transactional
     * fun logAuditEvent(userId: String, action: String, resource: String) {
     *     val event = AuditEvent(userId, action, resource, Instant.now())
     *     auditRepository.save(event)
     *
     *     // No ordering needed - each audit event is independent
     *     outbox.schedule(
     *         payload = event,
     *         additionalContext = mapOf(
     *             "userId" to userId,
     *             "action" to action,
     *             "resource" to resource,
     *             "severity" to "INFO"
     *         )
     *     )
     * }
     * ```
     *
     * @example With global tracing context
     * ```kotlin
     * // Global provider (automatically applied)
     * @Component
     * class TracingContextProvider : OutboxContextProvider {
     *     override fun provide() = mapOf(
     *         "traceId" to MDC.get("traceId"),
     *         "service" to "payment-service"
     *     )
     * }
     *
     * @Transactional
     * fun sendNotification(notification: Notification) {
     *     notificationRepository.save(notification)
     *     outbox.schedule(
     *         payload = notification,
     *         additionalContext = mapOf(
     *             "recipientId" to notification.recipientId,
     *             "channel" to notification.channel
     *         )
     *     )
     *     // Final context: { traceId, service, recipientId, channel }
     * }
     * ```
     *
     * @see schedule(payload: Any, key: String, additionalContext: Map<String, String>)
     */
    override fun schedule(
        payload: Any,
        additionalContext: Map<String, String>,
    ) {
        schedule(
            payload = payload,
            key = UUID.randomUUID().toString(),
            additionalContext = additionalContext,
        )
    }

    /**
     * Schedules a record with auto-generated UUID key and additional context for processing.
     *
     * Convenience method for independent payloads that don't require strict ordering but need
     * event-specific context metadata. Generates a unique UUID key internally, distributing
     * load evenly across partitions in distributed deployments.
     *
     * ## Context Handling
     *
     * The final context stored with the outbox record is a merge of:
     * 1. **Global context** from all [io.namastack.outbox.context.OutboxContextProvider] beans
     * 2. **Additional context** passed via the `additionalContext` parameter
     *
     * If the same key appears in both sources, the additional context value takes precedence.
     *
     * ## When to Use
     *
     * Use this method when:
     * - Event ordering is not required (independent events)
     * - You need to attach event-specific context metadata
     * - Examples: audit events, notifications, analytics events with metadata
     *
     * For ordered processing, use `schedule(payload, key, additionalContext)` with a meaningful key.
     *
     * ## Processing Behavior
     *
     * 1. Generates UUID key via `UUID.randomUUID().toString()`
     * 2. Collects global context from all registered providers
     * 3. Merges global context with additional context
     * 4. Creates separate record for each applicable handler
     * 5. Persists atomically within the current transaction
     *
     * @param payload Domain object to be processed. Handlers are discovered
     *                based on payload type (including inheritance and interfaces).
     * @param additionalContext Event-specific context metadata to be stored with the record.
     *                          This is merged with global context from OutboxContextProvider beans.
     *
     * @throws IllegalStateException if called outside a transaction context
     *
     * @example
     * ```kotlin
     * @Transactional
     * fun logAuditEvent(userId: String, action: String, resource: String) {
     *     val event = AuditEvent(userId, action, resource, Instant.now())
     *     auditRepository.save(event)
     *
     *     // No ordering needed - each audit event is independent
     *     outbox.schedule(
     *         payload = event,
     *         additionalContext = mapOf(
     *             "userId" to userId,
     *             "action" to action,
     *             "resource" to resource,
     *             "severity" to "INFO"
     *         )
     *     )
     * }
     * ```
     *
     * @example With global tracing context
     * ```kotlin
     * // Global provider (automatically applied)
     * @Component
     * class TracingContextProvider : OutboxContextProvider {
     *     override fun provide() = mapOf(
     *         "traceId" to MDC.get("traceId"),
     *         "service" to "payment-service"
     *     )
     * }
     *
     * @Transactional
     * fun sendNotification(notification: Notification) {
     *     notificationRepository.save(notification)
     *     outbox.schedule(
     *         payload = notification,
     *         additionalContext = mapOf(
     *             "recipientId" to notification.recipientId,
     *             "channel" to notification.channel
     *         )
     *     )
     *     // Final context: { traceId, service, recipientId, channel }
     * }
     * ```
     *
     * @see schedule(payload: Any, key: String, additionalContext: Map<String, String>)
     */
    override fun schedule(payload: Any) {
        schedule(
            payload = payload,
            key = UUID.randomUUID().toString(),
            additionalContext = emptyMap(),
        )
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
