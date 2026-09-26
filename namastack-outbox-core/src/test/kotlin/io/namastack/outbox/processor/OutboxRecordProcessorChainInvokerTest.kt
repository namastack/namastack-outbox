package io.namastack.outbox.processor

import io.mockk.every
import io.mockk.mockk
import io.namastack.outbox.OutboxChannelNameProvider
import io.namastack.outbox.OutboxRecordTestFactory.outboxRecord
import io.namastack.outbox.instrumentation.OutboxInstrumentation
import io.namastack.outbox.instrumentation.OutboxRecordProcessingInvocation
import io.namastack.outbox.instrumentation.OutboxRecordProcessingOutcome
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class OutboxRecordProcessorChainInvokerTest {
    private val chain = mockk<OutboxRecordProcessor>()

    @Test
    fun `returns completed when chain handles record`() {
        val record = outboxRecord()
        every { chain.handle(record) } returns OutboxRecordProcessingOutcome.COMPLETED

        val outcome = invoker().process(record)

        assertThat(outcome).isEqualTo(OutboxRecordProcessingOutcome.COMPLETED)
    }

    @Test
    fun `returns retry scheduled when chain leaves record new`() {
        val record = outboxRecord()
        every { chain.handle(record) } returns OutboxRecordProcessingOutcome.RETRY_SCHEDULED

        val outcome = invoker().process(record)

        assertThat(outcome).isEqualTo(OutboxRecordProcessingOutcome.RETRY_SCHEDULED)
    }

    @Test
    fun `returns failed when chain marks record failed`() {
        val record = outboxRecord()
        every { chain.handle(record) } returns OutboxRecordProcessingOutcome.FAILED

        val outcome = invoker().process(record)

        assertThat(outcome).isEqualTo(OutboxRecordProcessingOutcome.FAILED)
    }

    @Test
    fun `instruments chain with record and channel`() {
        val record = outboxRecord(failureCount = 2)
        var capturedInvocation: OutboxRecordProcessingInvocation? = null
        var capturedOutcome: OutboxRecordProcessingOutcome? = null
        val instrumentation =
            object : OutboxInstrumentation {
                override fun processRecord(
                    invocation: OutboxRecordProcessingInvocation,
                    action: () -> OutboxRecordProcessingOutcome,
                ): OutboxRecordProcessingOutcome {
                    capturedInvocation = invocation
                    return action().also { capturedOutcome = it }
                }
            }
        every { chain.handle(record) } returns OutboxRecordProcessingOutcome.COMPLETED

        val outcome =
            invoker(
                instrumentation = instrumentation,
                channelNameProvider = OutboxChannelNameProvider { "orders" },
            ).process(record)

        assertThat(capturedInvocation?.record).isSameAs(record)
        assertThat(capturedInvocation?.channel).isEqualTo("orders")
        assertThat(outcome).isEqualTo(capturedOutcome)
    }

    @Test
    fun `propagates exact chain exception through instrumentation`() {
        val record = outboxRecord()
        val failure = IllegalStateException("failed")
        every { chain.handle(record) } throws failure

        assertThatThrownBy { invoker().process(record) }.isSameAs(failure)
    }

    private fun invoker(
        instrumentation: OutboxInstrumentation = OutboxInstrumentation.NOOP,
        channelNameProvider: OutboxChannelNameProvider = OutboxChannelNameProvider.DEFAULT,
    ) = OutboxRecordProcessorChainInvoker(
        recordProcessorChain = chain,
        instrumentationSupplier = { instrumentation },
        channelNameProviderSupplier = { channelNameProvider },
    )
}
