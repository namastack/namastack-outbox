package io.namastack.outbox

/**
 * Public API for scheduling records in the outbox.
 *
 * Ensures records are persisted atomically with database transactions and processed
 * reliably with automatic retry logic. Records are routed to registered handlers
 * based on payload type or handled generically.
 *
 * ## Context Handling
 *
 * Outbox records can include context metadata (key-value pairs) for tracing, debugging, and filtering.
 * Context is collected from two sources and merged:
 *
 * ### 1. Global Context (OutboxContextProvider)
 * Context that applies to **all outbox records** regardless of payload type.
 * Implement [io.namastack.outbox.context.OutboxContextProvider] interface:
 *
 * ```kotlin
 * @Component
 * class TracingContextProvider : OutboxContextProvider {
 *     override fun provide(): Map<String, String> {
 *         return mapOf(
 *             "traceId" to MDC.get("traceId"),
 *             "spanId" to MDC.get("spanId")
 *         ).filterValues { it != null }
 *     }
 * }
 * ```
 *
 * ### 2. Additional Context (Method Parameter)
 * Event-specific context passed via `additionalContext` parameter:
 *
 * ```kotlin
 * @Transactional
 * fun processPayment(payment: Payment) {
 *     paymentRepository.save(payment)
 *     outbox.schedule(
 *         payload = PaymentProcessedEvent(payment.id),
 *         key = "payment-${payment.id}",
 *         additionalContext = mapOf(
 *             "paymentMethod" to payment.method,
 *             "amount" to payment.amount.toString()
 *         )
 *     )
 * }
 * ```
 *
 * **Context Merging**: Global context from providers is merged with additional context.
 * If keys conflict, additional context wins (allows overriding global values).
 *
 * ## Processing Guarantees
 *
 * - **Ordering**: Records with the same key are processed sequentially
 * - **Delivery**: At-least-once delivery (exactly-once with idempotent handlers)
 * - **Retries**: Automatic retry with exponential backoff on failure
 * - **Handler Routing**: Typed handlers have priority, generic handlers serve as fallback
 *
 * @author Roland Beisel
 * @since 0.4.0
 * @see io.namastack.outbox.context.OutboxContextProvider
 */
interface Outbox {
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
    fun schedule(
        payload: Any,
        key: String,
        additionalContext: Map<String, String>,
    )

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
    fun schedule(
        payload: Any,
        key: String,
    )

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
    fun schedule(
        payload: Any,
        additionalContext: Map<String, String>,
    )

    /**
     * Schedules a record with auto-generated UUID key for processing.
     *
     * Convenience method for independent payloads that don't require strict ordering
     * or event-specific context. Generates a unique UUID key internally, distributing
     * load evenly across partitions in distributed deployments.
     *
     * ## Context Handling
     *
     * This method uses **only global context** from [io.namastack.outbox.context.OutboxContextProvider] beans.
     * If you need to add event-specific context, use `schedule(payload, additionalContext)` instead.
     *
     * ## When to Use
     *
     * Use this when event ordering is not required (e.g., independent audit events,
     * notifications, or analytics events) and no additional context is needed.
     *
     * ## Processing Behavior
     *
     * 1. Generates UUID key via `UUID.randomUUID().toString()`
     * 2. Collects global context from all registered providers
     * 3. Creates separate record for each applicable handler
     * 4. Persists atomically within the current transaction
     *
     * @param payload Domain object to be processed
     *
     * @throws IllegalStateException if called outside a transaction context
     *
     * @example
     * ```kotlin
     * @Transactional
     * fun logEvent(event: AuditEvent) {
     *     auditRepository.save(event)
     *     // No ordering needed - each event processed independently
     *     outbox.schedule(event)
     * }
     * ```
     *
     * @example With global context providers
     * ```kotlin
     * // Global providers (automatically applied to all records)
     * @Component
     * class TracingContextProvider : OutboxContextProvider {
     *     override fun provide() = mapOf("traceId" to MDC.get("traceId"))
     * }
     *
     * @Component
     * class TenantContextProvider : OutboxContextProvider {
     *     override fun provide() = mapOf("tenantId" to TenantContext.getCurrentTenantId())
     * }
     *
     * @Transactional
     * fun publishEvent(event: DomainEvent) {
     *     eventRepository.save(event)
     *     // Record automatically includes traceId and tenantId from global providers
     *     outbox.schedule(event)
     * }
     * ```
     *
     * @see schedule(payload: Any, additionalContext: Map<String, String>)
     */
    fun schedule(payload: Any)
}
