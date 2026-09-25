package io.namastack.outbox.handler.invoker

import io.namastack.outbox.OpenForProxy
import io.namastack.outbox.OutboxChannelNameProvider
import io.namastack.outbox.OutboxHandlerNotFoundException
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.handler.registry.OutboxHandlerRegistry
import io.namastack.outbox.instrumentation.OutboxHandlerInvocation
import io.namastack.outbox.instrumentation.OutboxHandlerKind
import io.namastack.outbox.instrumentation.OutboxInstrumentation

/**
 * Invokes the appropriate handler for a given record.
 *
 * Routes outbox records to their registered handlers based on the handler ID
 * stored in the record. Handles both typed and generic handlers
 * with the correct parameter passing.
 *
 * @param handlerRegistry Registry of all registered handlers
 * @param instrumentationSupplier Supplies instrumentation applied around each primary handler invocation
 * @param channelNameProviderSupplier Supplies the provider for the logical outbox channel name
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
@OpenForProxy
class OutboxHandlerInvoker(
    private val handlerRegistry: OutboxHandlerRegistry,
    instrumentationSupplier: () -> OutboxInstrumentation = { OutboxInstrumentation.NOOP },
    channelNameProviderSupplier: () -> OutboxChannelNameProvider = { OutboxChannelNameProvider.DEFAULT },
) {
    private val instrumentation: OutboxInstrumentation by lazy(instrumentationSupplier)
    private val channelNameProvider: OutboxChannelNameProvider by lazy(channelNameProviderSupplier)

    /**
     * Verifies that the handler referenced by a record is registered on this instance.
     *
     * This check is intentionally separate from [dispatch] so the processor can perform it before
     * entering handler-delivery failure handling.
     *
     * @param record The record whose handler registration should be verified
     * @throws OutboxHandlerNotFoundException if the record's handler is unavailable
     */
    fun ensureHandlerAvailable(record: OutboxRecord<*>) {
        requireHandler(record)
    }

    /**
     * Dispatches a record to its registered handler.
     *
     * Algorithm:
     * 1. Skip if payload is null (nothing to process)
     * 2. Look up handler by ID from the record
     * 3. Invoke the handler with payload and metadata
     *
     * The handler ID comes from [OutboxRecord.handlerId], which was stored when
     * the record was originally scheduled.
     *
     * If a handler method throws an exception, the original exception is automatically
     * unwrapped from InvocationTargetException (reflection wrapper) and rethrown.
     * This ensures retry policies can match against the actual exception types.
     *
     * Example:
     * ```kotlin
     * val invoker = OutboxHandlerInvoker(registry)
     * invoker.dispatch(record)
     * ```
     *
     * @param record The record to process
     * @throws OutboxHandlerNotFoundException if no handler with the given ID exists
     * @throws Throwable the original exception thrown by the handler (will trigger retries)
     */
    fun dispatch(record: OutboxRecord<*>) {
        instrumentation.invokeHandler(
            invocation =
                OutboxHandlerInvocation(
                    record = record,
                    handlerKind = OutboxHandlerKind.PRIMARY,
                    channel = channelNameProvider.getChannelName(),
                ),
            action = {
                val payload = record.payload ?: return@invokeHandler
                val metadata = OutboxHandlerContextFactory.metadata(record)

                val handler = requireHandler(record)

                handler.invoke(payload, metadata)
            },
        )
    }

    private fun requireHandler(record: OutboxRecord<*>) =
        handlerRegistry.getHandlerById(record.handlerId)
            ?: throw OutboxHandlerNotFoundException(
                recordId = record.id,
                recordKey = record.key,
                handlerId = record.handlerId,
            )
}
