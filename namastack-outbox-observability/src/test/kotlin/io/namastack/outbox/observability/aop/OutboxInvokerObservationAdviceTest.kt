package io.namastack.outbox.observability.aop

import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import io.mockk.every
import io.mockk.mockk
import io.namastack.outbox.OutboxChannelNameProvider
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.observability.OutboxMetricKeyNames
import io.namastack.outbox.observability.OutboxMetricNames
import io.namastack.outbox.observability.OutboxProcessObservationContext
import io.namastack.outbox.observability.OutboxProcessObservationContext.HandlerKind
import org.aopalliance.intercept.MethodInvocation
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@DisplayName("OutboxInvokerObservationAdvice")
class OutboxInvokerObservationAdviceTest {
    private val meterRegistry = SimpleMeterRegistry()
    private val observationRegistry = ObservationRegistry.create()
    private val capturingHandler = CapturingObservationHandler<OutboxProcessObservationContext>()

    init {
        observationRegistry
            .observationConfig()
            .observationHandler(capturingHandler)
            .observationHandler(DefaultMeterObservationHandler(meterRegistry))
    }

    @Nested
    @DisplayName("Handler Observation")
    inner class HandlerObservation {
        @Test
        fun `records primary handler processing observation and timer`() {
            val record = outboxRecord()
            val invocation = dispatchInvocation(record)
            val advice = observationAdvice(HandlerKind.PRIMARY)

            advice.invoke(invocation)

            val context = capturingHandler.stopped.single()
            assertThat(context.name).isEqualTo(OutboxMetricNames.RECORD_PROCESS)
            assertThat(context.contextualName).isEqualTo("outbox process")
            assertThat(context.getHandlerKind()).isEqualTo(HandlerKind.PRIMARY)
            assertThat(context.getHandlerId()).isEqualTo("order-handler")
            assertThat(context.getRecordId()).isEqualTo(record.id)
            assertThat(context.getRecordKey()).isEqualTo("order-123")
            assertThat(context.getDeliveryAttempt()).isEqualTo(1)
            assertThat(context.getChannel()).isEqualTo("orders")
            assertThat(context.lowCardinalityValue(OutboxMetricKeyNames.LowCardinality.HANDLER_KIND))
                .isEqualTo("primary")
            assertThat(context.lowCardinalityValue(OutboxMetricKeyNames.LowCardinality.HANDLER_ID))
                .isEqualTo("order-handler")
            assertThat(context.lowCardinalityValue(OutboxMetricKeyNames.LowCardinality.CHANNEL)).isEqualTo("orders")
            assertThat(context.highCardinalityValue(OutboxMetricKeyNames.HighCardinality.RECORD_ID))
                .isEqualTo(record.id)
            assertThat(context.highCardinalityValue(OutboxMetricKeyNames.HighCardinality.RECORD_KEY))
                .isEqualTo("order-123")
            assertThat(context.highCardinalityValue(OutboxMetricKeyNames.HighCardinality.DELIVERY_ATTEMPT))
                .isEqualTo("1")

            assertThat(
                meterRegistry
                    .get(OutboxMetricNames.RECORD_PROCESS)
                    .tag(OutboxMetricKeyNames.LowCardinality.HANDLER_KIND, "primary")
                    .tag(OutboxMetricKeyNames.LowCardinality.HANDLER_ID, "order-handler")
                    .tag(OutboxMetricKeyNames.LowCardinality.CHANNEL, "orders")
                    .timer()
                    .count(),
            ).isEqualTo(1)
        }

        @Test
        fun `records fallback handler kind`() {
            observationAdvice(HandlerKind.FALLBACK).invoke(dispatchInvocation(outboxRecord()))

            assertThat(capturingHandler.stopped.single().getHandlerKind()).isEqualTo(HandlerKind.FALLBACK)
            assertThat(
                meterRegistry
                    .get(OutboxMetricNames.RECORD_PROCESS)
                    .tag(OutboxMetricKeyNames.LowCardinality.HANDLER_KIND, "fallback")
                    .tag(OutboxMetricKeyNames.LowCardinality.HANDLER_ID, "order-handler")
                    .tag(OutboxMetricKeyNames.LowCardinality.CHANNEL, "orders")
                    .timer()
                    .count(),
            ).isEqualTo(1)
        }

        @Test
        fun `records failed processing observation and rethrows`() {
            val failure = IllegalStateException("handler failed")
            val invocation = mockk<MethodInvocation>()
            every { invocation.arguments } returns arrayOf(outboxRecord())
            every { invocation.proceed() } throws failure

            assertThatThrownBy { observationAdvice(HandlerKind.PRIMARY).invoke(invocation) }.isSameAs(failure)

            assertThat(capturingHandler.stopped.single().error).isSameAs(failure)
        }
    }

    @Nested
    @DisplayName("Guard Paths")
    inner class GuardPaths {
        @Test
        fun `proceeds without observation when first argument is not an outbox record`() {
            val invocation = mockk<MethodInvocation>()
            every { invocation.arguments } returns arrayOf("not-a-record")
            every { invocation.proceed() } returns Unit

            observationAdvice(HandlerKind.PRIMARY).invoke(invocation)

            assertThat(capturingHandler.stopped).isEmpty()
        }
    }

    private fun observationAdvice(handlerKind: HandlerKind): OutboxInvokerObservationAdvice =
        OutboxInvokerObservationAdvice(
            handlerKind = handlerKind,
            observationRegistrySupplier = { observationRegistry },
            customOutboxConventionSupplier = { null },
            channelNameProviderSupplier = { OutboxChannelNameProvider { "orders" } },
        )

    private fun dispatchInvocation(record: OutboxRecord<*>): MethodInvocation {
        val invocation = mockk<MethodInvocation>()
        every { invocation.arguments } returns arrayOf(record)
        every { invocation.proceed() } returns Unit
        return invocation
    }

    private fun outboxRecord(): OutboxRecord<ProcessPayload> =
        OutboxRecord
            .Builder<ProcessPayload>()
            .key("order-123")
            .payload(ProcessPayload("created"))
            .context(mapOf("traceparent" to "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-00"))
            .handlerId("order-handler")
            .build(Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC))

    private data class ProcessPayload(
        val type: String,
    )
}

internal class CapturingObservationHandler<T : Observation.Context> : ObservationHandler<T> {
    val stopped = mutableListOf<T>()

    override fun onStop(context: T) {
        stopped += context
    }

    override fun supportsContext(context: Observation.Context): Boolean = true
}

internal fun Observation.Context.lowCardinalityValue(key: String): String? = getLowCardinalityKeyValue(key)?.value

internal fun Observation.Context.highCardinalityValue(key: String): String? = getHighCardinalityKeyValue(key)?.value
