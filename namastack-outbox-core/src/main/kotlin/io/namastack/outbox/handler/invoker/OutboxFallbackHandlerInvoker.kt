package io.namastack.outbox.handler.invoker

import io.namastack.outbox.OpenForProxy
import io.namastack.outbox.OutboxChannelNameProvider
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.handler.registry.OutboxHandlerRegistry
import io.namastack.outbox.instrumentation.OutboxInstrumentation
import io.namastack.outbox.instrumentation.OutboxProcessHandlerKind
import io.namastack.outbox.instrumentation.OutboxProcessInvocation
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry
import kotlin.LazyThreadSafetyMode.SYNCHRONIZED

/**
 * Invokes fallback handlers for failed outbox records.
 *
 * Routes failed records to their registered fallback handlers based on handler ID
 * from the record's metadata.
 *
 * @param retryPolicyRegistry Registry to look up default retry policies per handler
 * @param handlerRegistry Registry used to resolve fallbacks and explicit retry policies
 * @param instrumentationSupplier Supplies instrumentation applied around each fallback handler invocation
 * @param channelNameProviderSupplier Supplies the provider for the logical outbox channel name
 * @author Roland Beisel
 * @since 1.0.0
 */
@OpenForProxy
class OutboxFallbackHandlerInvoker internal constructor(
    private val retryPolicyRegistry: OutboxRetryPolicyRegistry,
    private val handlerRegistry: OutboxHandlerRegistry,
    instrumentationSupplier: () -> OutboxInstrumentation = { OutboxInstrumentation.NOOP },
    channelNameProviderSupplier: () -> OutboxChannelNameProvider = { OutboxChannelNameProvider.DEFAULT },
) {
    private val instrumentation: OutboxInstrumentation by lazy(SYNCHRONIZED, instrumentationSupplier)
    private val channelNameProvider: OutboxChannelNameProvider by lazy(SYNCHRONIZED, channelNameProviderSupplier)

    /**
     * Invokes the fallback handler for a failed record.
     *
     * Looks up the fallback handler by handlerId from the record's metadata and invokes it
     * with the record's payload and failure context. Returns early if the payload is null.
     *
     * @param record The failed record to dispatch to a fallback handler
     * @throws IllegalStateException if no fallback handler is registered for the record's handlerId
     * or if the record does not contain a failure exception (which is expected for failed records)
     */
    fun dispatch(record: OutboxRecord<*>) {
        instrumentation.process(
            invocation =
                OutboxProcessInvocation(
                    record = record,
                    handlerKind = OutboxProcessHandlerKind.FALLBACK,
                    channel = channelNameProvider.getChannelName(),
                ),
            action = {
                val payload = record.payload ?: return@process
                val failureException = getFailureException(record)
                val registration =
                    handlerRegistry.getRegistrationById(record.handlerId)
                val fallback =
                    registration?.fallback
                        ?: throw IllegalStateException("No fallback handler with id ${record.handlerId}")

                val context =
                    OutboxHandlerContextFactory.failure(
                        record,
                        failureException,
                        retryPolicyRegistry,
                        registration.explicitRetryPolicy,
                    )

                fallback.invoke(payload, context)
            },
        )
    }

    /**
     * Gets the failure exception from a record.
     * The exception must be present since this invoker only handles failed records.
     */
    private fun getFailureException(record: OutboxRecord<*>): Throwable =
        checkNotNull(record.failureException) {
            "Expected failure exception in record ${record.id} but found none"
        }
}
