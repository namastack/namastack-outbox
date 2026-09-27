package io.namastack.outbox.processor

import io.namastack.outbox.OutboxChannelNameProvider
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.instrumentation.OutboxInstrumentation
import io.namastack.outbox.instrumentation.OutboxRecordProcessingInvocation
import io.namastack.outbox.instrumentation.OutboxRecordProcessingOutcome

/**
 * Invokes the configured processor chain at the record-processing instrumentation boundary.
 *
 * @param recordProcessorChain Root of the record processor chain.
 * @param instrumentationSupplier Supplies instrumentation around each chain invocation.
 * @param channelNameProviderSupplier Supplies the logical outbox channel name.
 *
 * @author Aleksander Zamojski
 * @since 1.10.0
 */
class OutboxRecordProcessorChainInvoker(
    private val recordProcessorChain: OutboxRecordProcessor,
    instrumentationSupplier: () -> OutboxInstrumentation = { OutboxInstrumentation.NOOP },
    channelNameProviderSupplier: () -> OutboxChannelNameProvider = { OutboxChannelNameProvider.DEFAULT },
) {
    private val instrumentation: OutboxInstrumentation by lazy(instrumentationSupplier)
    private val channelNameProvider: OutboxChannelNameProvider by lazy(channelNameProviderSupplier)

    /**
     * Processes one fully materialized record and returns its normal terminal outcome.
     *
     * Exceptions from the processor chain or instrumentation propagate unchanged.
     *
     * @param record Record entering the processor chain.
     * @return The outcome returned by the processor chain.
     */
    fun process(record: OutboxRecord<*>): OutboxRecordProcessingOutcome {
        val invocation =
            OutboxRecordProcessingInvocation(
                record = record,
                channel = channelNameProvider.getChannelName(),
            )

        return instrumentation.processRecord(invocation) {
            recordProcessorChain.handle(record)
        }
    }
}
