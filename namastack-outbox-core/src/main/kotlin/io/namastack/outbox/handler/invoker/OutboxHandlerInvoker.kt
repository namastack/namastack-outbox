package io.namastack.outbox.handler.invoker

import io.namastack.outbox.OpenForProxy
import io.namastack.outbox.OutboxChannelNameProvider
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.handler.registry.OutboxHandlerRegistry
import io.namastack.outbox.instrumentation.OutboxInstrumentation
import io.namastack.outbox.instrumentation.OutboxProcessHandlerKind
import io.namastack.outbox.instrumentation.OutboxProcessInvocation

/**
 * Invokes the appropriate handler for a given record.
 *
 * Routes outbox records to their registered handlers based on the handler ID
 * stored in the record. Handles both typed and generic handlers
 * with the correct parameter passing.
 *
 * @param handlerRegistry Registry of all registered handlers
 * @param instrumentation Instrumentation applied around each primary handler invocation
 * @param channelNameProvider Provider for the logical outbox channel name
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
@OpenForProxy
class OutboxHandlerInvoker(
    private val handlerRegistry: OutboxHandlerRegistry,
    private val instrumentation: OutboxInstrumentation = OutboxInstrumentation.NOOP,
    private val channelNameProvider: OutboxChannelNameProvider = OutboxChannelNameProvider.DEFAULT,
) {
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
     * @throws IllegalStateException if no handler with the given ID exists
     * @throws Throwable the original exception thrown by the handler (will trigger retries)
     */
    fun dispatch(record: OutboxRecord<*>) {
        instrumentation.process(
            invocation =
                OutboxProcessInvocation(
                    record = record,
                    handlerKind = OutboxProcessHandlerKind.PRIMARY,
                    channel = channelNameProvider.getChannelName(),
                ),
            action = {
                val payload = record.payload ?: return@process
                val metadata = OutboxHandlerContextFactory.metadata(record)

                val handler =
                    handlerRegistry.getHandlerById(record.handlerId)
                        ?: throw IllegalStateException("No handler with id ${record.handlerId}")

                handler.invoke(payload, metadata)
            },
        )
    }
}
