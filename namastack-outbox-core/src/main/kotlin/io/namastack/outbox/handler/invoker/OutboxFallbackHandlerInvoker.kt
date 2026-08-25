package io.namastack.outbox.handler.invoker

import io.namastack.outbox.OpenForProxy
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.handler.registry.OutboxFallbackHandlerRegistry
import io.namastack.outbox.handler.registry.OutboxHandlerRegistry
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry

/**
 * Invokes fallback handlers for failed outbox records.
 *
 * Routes failed records to their registered fallback handlers based on handler ID
 * from the record's metadata.
 *
 * @param retryPolicyRegistry Registry to look up default retry policies per handler
 * @param registrationLookup Resolves the fallback handler and any explicit retry policy by routing ID
 * @author Roland Beisel
 * @since 1.0.0
 */
@OpenForProxy
class OutboxFallbackHandlerInvoker private constructor(
    private val retryPolicyRegistry: OutboxRetryPolicyRegistry,
    private val registrationLookup: (String) -> FallbackInvocationTarget?,
) {
    constructor(
        retryPolicyRegistry: OutboxRetryPolicyRegistry,
        fallbackHandlerRegistry: OutboxFallbackHandlerRegistry,
    ) : this(
        retryPolicyRegistry,
        { id ->
            fallbackHandlerRegistry.getByHandlerId(id)?.let {
                FallbackInvocationTarget(it, null)
            }
        },
    )

    internal constructor(
        retryPolicyRegistry: OutboxRetryPolicyRegistry,
        handlerRegistry: OutboxHandlerRegistry,
    ) : this(
        retryPolicyRegistry,
        { id ->
            handlerRegistry.getRegistrationById(id)?.let { registration ->
                registration.fallback?.let {
                    FallbackInvocationTarget(it, registration.explicitRetryPolicy)
                }
            }
        },
    )

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
        val payload = record.payload ?: return
        val failureException = getFailureException(record)
        val registration =
            registrationLookup(record.handlerId)
                ?: throw IllegalStateException("No fallback handler with id ${record.handlerId}")

        val context =
            OutboxHandlerContextFactory.failure(
                record,
                failureException,
                retryPolicyRegistry,
                registration.explicitRetryPolicy,
            )

        registration.fallback.invoke(payload, context)
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
