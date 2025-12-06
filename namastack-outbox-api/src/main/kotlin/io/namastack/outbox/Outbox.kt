package io.namastack.outbox

/**
 * Public API for scheduling records in the outbox.
 *
 * Ensures records are persisted atomically with database transactions and processed
 * reliably with automatic retry logic. Records are routed to registered handlers
 * based on payload type or handled generically.
 *
 * ## Example
 *
 * ```kotlin
 * @Transactional
 * fun createOrder(order: Order) {
 *     // ... save order to database ...
 *     outbox.schedule(OrderCreatedEvent(order.id), key = "order-${order.id}")
 * }
 * ```
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
 */
interface Outbox {
    /**
     * Schedules a record with an explicit key for processing.
     *
     * Records with the same key are processed sequentially by registered handlers,
     * ensuring strict ordering for related events. The key also determines partition
     * assignment in distributed environments.
     *
     * ## Processing Behavior
     *
     * 1. **Handler Discovery**: Finds all handlers registered for the payload type
     * 2. **Record Creation**: Creates separate record for each handler
     * 3. **Persistence**: Saves atomically within the current transaction
     * 4. **Routing**: Typed handlers receive the record, generic handlers receive metadata
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
     */
    fun schedule(
        payload: Any,
        key: String,
    )

    /**
     * Schedules a record with auto-generated UUID key for processing.
     *
     * Convenience method for independent payloads that don't require strict ordering.
     * Generates a unique UUID key internally, distributing load evenly across
     * partitions in distributed deployments.
     *
     * Use this when event ordering is not required (e.g., independent audit events,
     * notifications, or analytics events).
     *
     * @param payload Domain object to be processed
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
     */
    fun schedule(payload: Any)
}
