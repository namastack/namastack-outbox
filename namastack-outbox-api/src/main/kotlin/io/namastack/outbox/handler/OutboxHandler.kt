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
     * Returns the optional stable identity of this generic handler declaration.
     *
     * `null` retains the generated ID, or the Spring bean name for a lambda. To migrate an ID,
     * deploy the future ID as an alias before promoting it to the canonical ID.
     *
     * @return The generic handler identity, or `null` to use the generated identity
     */
    fun getGenericHandlerIdentity(): OutboxHandlerIdentity? = null

    /**
     * Returns the processing group of this generic handler declaration.
     *
     * Names must follow the rules in [OutboxProcessingGroup]. This setting is independent of
     * the other handler declarations on the same bean.
     *
     * @return The declared group, or [OutboxProcessingGroup.DEFAULT] when not overridden
     * @since 1.12.0
     */
    fun getGenericHandlerProcessingGroup(): String = OutboxProcessingGroup.DEFAULT

    /**
     * Determines whether this handler supports scheduling for a given payload.
     *
     * Called before an outbox record is created. Return `false` to prevent
     * record creation for this handler. [OutboxRecordMetadata.recordId] is `null` at this stage
     * because no record has been created yet.
     *
     * Default implementation is `true` for backward compatibility.
     *
     * @param payload The record payload of any type
     * @param metadata Record metadata containing context information
     * @return `true` if a record should be scheduled for this handler
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
