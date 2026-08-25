package io.namastack.outbox.handler

/**
 * Handler for processing outbox records of any payload type.
 *
 * Receives all records with their payload and metadata. Use for multi-type or
 * centralized processing logic.
 *
 * ## Example
 *
 * ```kotlin
 * @Component
 * class MyHandler : OutboxHandler {
 *     override fun handle(payload: Any, metadata: OutboxRecordMetadata) {
 *         when (payload) {
 *             is OrderCreated -> handleOrder(payload)
 *             is PaymentProcessed -> handlePayment(payload)
 *         }
 *     }
 * }
 * ```
 *
 * ## Invocation with Typed Handlers
 *
 * If both a typed handler ([OutboxTypedHandler<T>]) and this generic handler
 * are registered for the same payload type, both will be called:
 * 1. Typed handler is invoked first (if payload type matches)
 * 2. Generic handler is invoked second (always)
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
interface OutboxHandler {
    /**
     * Optional stable canonical ID written to newly scheduled records.
     * `null` retains the generated ID (or the Spring bean name for a lambda).
     */
    fun getHandlerId(): String? = null

    /**
     * Alternative, lookup-only IDs accepted for persisted records.
     * To migrate, deploy a future ID here before returning it from [getHandlerId].
     */
    fun getHandlerAliases(): Set<String> = emptySet()

    /**
     * Determines whether this handler supports scheduling for a given payload.
     *
     * Called before an outbox record is created. Return `false` to prevent
     * record creation for this handler.
     *
     * Default implementation is `true` for backward compatibility.
     *
     * @param payload The record payload of any type
     * @param metadata Record metadata containing context information
     */
    fun supports(
        payload: Any,
        metadata: OutboxRecordMetadata,
    ): Boolean = true

    /**
     * Handles an outbox record.
     *
     * @param payload The record payload of any type
     * @param metadata Record metadata containing context information
     * @throws Exception to trigger retries based on configured retry policy
     */
    fun handle(
        payload: Any,
        metadata: OutboxRecordMetadata,
    )
}
