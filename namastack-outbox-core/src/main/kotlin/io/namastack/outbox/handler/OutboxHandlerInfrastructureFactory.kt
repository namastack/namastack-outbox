package io.namastack.outbox.handler

import io.namastack.outbox.instrumentation.OutboxInstrumentation
import io.namastack.outbox.retry.OutboxRetryPolicy
import org.springframework.beans.factory.BeanFactory

/**
 * Creates isolated handler infrastructure using the application instrumentation beans.
 *
 * @param beanFactory Spring bean factory used to resolve named retry policies
 * @param instrumentationsSupplier Supplies the instrumentation beans to compose for each
 * infrastructure
 *
 * @author Roland Beisel
 * @since 1.10.0
 */
class OutboxHandlerInfrastructureFactory internal constructor(
    private val beanFactory: BeanFactory,
    private val instrumentationsSupplier: () -> List<OutboxInstrumentation>,
) {
    /**
     * Creates empty handler infrastructure for one logical channel.
     *
     * @param defaultRetryPolicy Default retry policy for handlers without an explicit policy
     * @param channelName Logical channel name used by instrumentation
     * @return Isolated infrastructure ready for handler registration
     */
    fun create(
        defaultRetryPolicy: OutboxRetryPolicy,
        channelName: String,
    ): OutboxHandlerInfrastructure =
        OutboxHandlerInfrastructure(
            beanFactory = beanFactory,
            defaultRetryPolicy = defaultRetryPolicy,
            instrumentation = OutboxInstrumentation.compose(instrumentationsSupplier()),
            channelName = channelName,
        )
}
