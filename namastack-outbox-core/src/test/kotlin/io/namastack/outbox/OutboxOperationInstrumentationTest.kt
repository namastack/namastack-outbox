package io.namastack.outbox

import io.mockk.every
import io.mockk.mockk
import io.namastack.outbox.context.OutboxContextCollector
import io.namastack.outbox.handler.assembly.HandlerRegistration
import io.namastack.outbox.handler.invoker.OutboxFallbackHandlerInvoker
import io.namastack.outbox.handler.invoker.OutboxHandlerInvoker
import io.namastack.outbox.handler.method.fallback.OutboxFallbackHandlerMethod
import io.namastack.outbox.handler.method.handler.TypedHandlerMethod
import io.namastack.outbox.handler.registry.OutboxHandlerRegistry
import io.namastack.outbox.instrumentation.OutboxInstrumentation
import io.namastack.outbox.instrumentation.OutboxProcessHandlerKind
import io.namastack.outbox.instrumentation.OutboxProcessInvocation
import io.namastack.outbox.instrumentation.OutboxScheduleInvocation
import io.namastack.outbox.retry.OutboxRetryPolicy
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

class OutboxOperationInstrumentationTest {
    @Test
    fun `instruments every schedule overload exactly once with documented key semantics`() {
        val scheduleInvocations = mutableListOf<OutboxScheduleInvocation>()
        val instrumentationResolutions = AtomicInteger()
        val channelNameProviderResolutions = AtomicInteger()
        val instrumentation =
            instrumentation(
                schedule = { invocation, action ->
                    scheduleInvocations += invocation
                    action()
                },
            )
        val contextCollector = mockk<OutboxContextCollector>()
        val handlerRegistry = mockk<OutboxHandlerRegistry>()
        val recordRepository = mockk<OutboxRecordRepository>(relaxed = true)
        every { contextCollector.collectContext() } returns emptyMap()
        every { handlerRegistry.getHandlersForPayloadType(any()) } returns emptyList()
        every { handlerRegistry.getGenericHandlers(any(), any()) } returns emptyList()
        val outbox =
            OutboxService(
                contextCollector = contextCollector,
                handlerRegistry = handlerRegistry,
                outboxRecordRepository = recordRepository,
                clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
                instrumentationSupplier = {
                    instrumentationResolutions.incrementAndGet()
                    instrumentation
                },
                channelNameProviderSupplier = {
                    channelNameProviderResolutions.incrementAndGet()
                    OutboxChannelNameProvider { "orders" }
                },
            )
        val payload = SchedulePayload("created")

        outbox.schedule(payload, "order-1", mapOf("tenant" to "north"))
        outbox.schedule(payload, "order-2")
        outbox.schedule(payload, mapOf("tenant" to "south"))
        outbox.schedule(payload)

        assertThat(scheduleInvocations).hasSize(4)
        assertThat(scheduleInvocations.map { it.recordKey })
            .containsExactly("order-1", "order-2", "auto-generated", "auto-generated")
        assertThat(scheduleInvocations.map { it.channel }).containsOnly("orders")
        assertThat(scheduleInvocations.map { it.payload }).containsOnly(payload)
        assertThat(instrumentationResolutions).hasValue(1)
        assertThat(channelNameProviderResolutions).hasValue(1)
    }

    @Test
    fun `instruments the primary handler invocation`() {
        val events = mutableListOf<String>()
        val processInvocations = mutableListOf<OutboxProcessInvocation>()
        val instrumentation = recordingProcessInstrumentation(events, processInvocations)
        val handlerRegistry = mockk<OutboxHandlerRegistry>()
        val handler = mockk<TypedHandlerMethod>()
        val record = record()
        every { handlerRegistry.getHandlerById("handler-1") } returns handler
        every { handler.invoke(any(), any()) } answers {
            events += "handler"
        }
        val invoker =
            OutboxHandlerInvoker(
                handlerRegistry = handlerRegistry,
                instrumentationSupplier = { instrumentation },
                channelNameProviderSupplier = { OutboxChannelNameProvider { "orders" } },
            )

        invoker.dispatch(record)

        assertThat(events).containsExactly("before", "handler", "after")
        assertThat(processInvocations).hasSize(1)
        assertThat(processInvocations.single().record).isSameAs(record)
        assertThat(processInvocations.single().handlerKind).isEqualTo(OutboxProcessHandlerKind.PRIMARY)
        assertThat(processInvocations.single().channel).isEqualTo("orders")
    }

    @Test
    fun `instruments the fallback handler invocation`() {
        val events = mutableListOf<String>()
        val processInvocations = mutableListOf<OutboxProcessInvocation>()
        val instrumentation = recordingProcessInstrumentation(events, processInvocations)
        val retryPolicyRegistry = mockk<OutboxRetryPolicyRegistry>()
        val handlerRegistry = mockk<OutboxHandlerRegistry>()
        val retryPolicy = mockk<OutboxRetryPolicy>()
        val fallbackHandler = mockk<OutboxFallbackHandlerMethod>()
        val record = record(failureException = IllegalStateException("primary failed"))
        every { retryPolicyRegistry.getByHandlerId("handler-1") } returns retryPolicy
        every { retryPolicy.maxRetries() } returns 3
        every { retryPolicy.shouldRetry(any()) } returns false
        every { handlerRegistry.getRegistrationById("handler-1") } returns
            HandlerRegistration(
                beanName = "handler",
                primary = mockk(),
                fallback = fallbackHandler,
                explicitRetryPolicy = null,
            )
        every { fallbackHandler.invoke(any(), any()) } answers {
            events += "handler"
        }
        val invoker =
            OutboxFallbackHandlerInvoker(
                retryPolicyRegistry = retryPolicyRegistry,
                handlerRegistry = handlerRegistry,
                instrumentationSupplier = { instrumentation },
                channelNameProviderSupplier = { OutboxChannelNameProvider { "payments" } },
            )

        invoker.dispatch(record)

        assertThat(events).containsExactly("before", "handler", "after")
        assertThat(processInvocations).hasSize(1)
        assertThat(processInvocations.single().record).isSameAs(record)
        assertThat(processInvocations.single().handlerKind).isEqualTo(OutboxProcessHandlerKind.FALLBACK)
        assertThat(processInvocations.single().channel).isEqualTo("payments")
    }

    private fun recordingProcessInstrumentation(
        events: MutableList<String>,
        invocations: MutableList<OutboxProcessInvocation>,
    ): OutboxInstrumentation =
        instrumentation(
            process = { invocation, action ->
                invocations += invocation
                events += "before"
                try {
                    action()
                } finally {
                    events += "after"
                }
            },
        )

    private fun instrumentation(
        schedule: (OutboxScheduleInvocation, () -> Unit) -> Unit = { _, action -> action() },
        process: (OutboxProcessInvocation, () -> Unit) -> Unit = { _, action -> action() },
    ): OutboxInstrumentation =
        object : OutboxInstrumentation {
            override fun schedule(
                invocation: OutboxScheduleInvocation,
                action: () -> Unit,
            ) = schedule(invocation, action)

            override fun process(
                invocation: OutboxProcessInvocation,
                action: () -> Unit,
            ) = process(invocation, action)
        }

    private fun record(failureException: Throwable? = null): OutboxRecord<String> {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        return OutboxRecord.restore(
            id = "record-1",
            recordKey = "order-1",
            payload = "payload",
            context = emptyMap(),
            createdAt = now,
            status = OutboxRecordStatus.NEW,
            completedAt = null,
            failureCount = if (failureException == null) 0 else 1,
            failureException = failureException,
            failureReason = failureException?.message,
            partition = 1,
            nextRetryAt = now,
            handlerId = "handler-1",
        )
    }

    private data class SchedulePayload(
        val state: String,
    )
}
