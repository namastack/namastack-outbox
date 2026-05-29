package io.namastack.outbox.observability.aop

import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.ObservationRegistry
import io.mockk.every
import io.mockk.mockk
import io.namastack.outbox.OutboxChannelNameProvider
import io.namastack.outbox.observability.OutboxMetricKeyNames
import io.namastack.outbox.observability.OutboxMetricNames
import io.namastack.outbox.observability.OutboxScheduleObservationContext
import org.aopalliance.intercept.MethodInvocation
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("OutboxScheduleObservationAdvice")
class OutboxScheduleObservationAdviceTest {
    private val meterRegistry = SimpleMeterRegistry()
    private val observationRegistry = ObservationRegistry.create()
    private val capturingHandler = CapturingObservationHandler<OutboxScheduleObservationContext>()

    init {
        observationRegistry
            .observationConfig()
            .observationHandler(capturingHandler)
            .observationHandler(DefaultMeterObservationHandler(meterRegistry))
    }

    private val advice =
        OutboxScheduleObservationAdvice(
            observationRegistrySupplier = { observationRegistry },
            customOutboxConventionSupplier = { null },
            channelNameProviderSupplier = { OutboxChannelNameProvider { "orders" } },
        )

    @Nested
    @DisplayName("Schedule Observation")
    inner class ScheduleObservation {
        @Test
        fun `records schedule observation and timer for explicit key`() {
            val invocation = scheduleInvocation(arrayOf(SchedulePayload("created"), "order-123"))

            advice.invoke(invocation)

            val context = capturingHandler.stopped.single()
            assertThat(context.name).isEqualTo(OutboxMetricNames.RECORD_SCHEDULE)
            assertThat(context.contextualName).isEqualTo("outbox schedule")
            assertThat(context.payloadType).isEqualTo("SchedulePayload")
            assertThat(context.recordKey).isEqualTo("order-123")
            assertThat(context.channel).isEqualTo("orders")
            assertThat(context.lowCardinalityValue(OutboxMetricKeyNames.LowCardinality.CHANNEL)).isEqualTo("orders")
            assertThat(context.highCardinalityValue(OutboxMetricKeyNames.HighCardinality.SCHEDULE_RECORD_KEY))
                .isEqualTo("order-123")
            assertThat(context.highCardinalityValue(OutboxMetricKeyNames.HighCardinality.SCHEDULE_PAYLOAD_TYPE))
                .isEqualTo("SchedulePayload")

            assertThat(
                meterRegistry
                    .get(OutboxMetricNames.RECORD_SCHEDULE)
                    .tag(OutboxMetricKeyNames.LowCardinality.CHANNEL, "orders")
                    .timer()
                    .count(),
            ).isEqualTo(1)
        }

        @Test
        fun `records auto-generated placeholder when schedule key is not an argument`() {
            val invocation = scheduleInvocation(arrayOf(SchedulePayload("created")))

            advice.invoke(invocation)

            val context = capturingHandler.stopped.single()
            assertThat(context.recordKey).isEqualTo("auto-generated")
            assertThat(context.highCardinalityValue(OutboxMetricKeyNames.HighCardinality.SCHEDULE_RECORD_KEY))
                .isEqualTo("auto-generated")
        }

        @Test
        fun `does not treat additional context as schedule key`() {
            val invocation = scheduleInvocation(arrayOf(SchedulePayload("created"), mapOf("orderId" to "123")))

            advice.invoke(invocation)

            assertThat(capturingHandler.stopped.single().recordKey).isEqualTo("auto-generated")
        }

        @Test
        fun `records failed schedule observation and rethrows`() {
            val failure = IllegalStateException("schedule failed")
            val invocation = mockk<MethodInvocation>()
            every { invocation.arguments } returns arrayOf(SchedulePayload("created"), "order-123")
            every { invocation.proceed() } throws failure

            assertThatThrownBy { advice.invoke(invocation) }.isSameAs(failure)

            assertThat(capturingHandler.stopped.single().error).isSameAs(failure)
        }
    }

    @Nested
    @DisplayName("Guard Paths")
    inner class GuardPaths {
        @Test
        fun `proceeds without observation when payload argument is missing`() {
            val invocation = scheduleInvocation(emptyArray())

            advice.invoke(invocation)

            assertThat(capturingHandler.stopped).isEmpty()
        }
    }

    private fun scheduleInvocation(arguments: Array<Any?>): MethodInvocation {
        val invocation = mockk<MethodInvocation>()
        every { invocation.arguments } returns arguments
        every { invocation.proceed() } returns Unit
        return invocation
    }

    private data class SchedulePayload(
        val type: String,
    )
}
