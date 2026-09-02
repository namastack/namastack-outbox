package io.namastack.outbox.observability

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.instrumentation.OutboxProcessHandlerKind.FALLBACK
import io.namastack.outbox.instrumentation.OutboxProcessHandlerKind.PRIMARY
import io.namastack.outbox.instrumentation.OutboxProcessInvocation
import io.namastack.outbox.instrumentation.OutboxScheduleInvocation
import io.namastack.outbox.observability.OutboxProcessObservationContext.HandlerKind
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

class MicrometerOutboxInstrumentationTest {
    private val observationRegistry = ObservationRegistry.create()
    private val scheduleContexts = mutableListOf<OutboxScheduleObservationContext>()
    private val processContexts = mutableListOf<OutboxProcessObservationContext>()

    init {
        observationRegistry.observationConfig().observationHandler(
            object : ObservationHandler<OutboxScheduleObservationContext> {
                override fun onStop(context: OutboxScheduleObservationContext) {
                    scheduleContexts += context
                }

                override fun supportsContext(context: Observation.Context): Boolean =
                    context is OutboxScheduleObservationContext
            },
        )
        observationRegistry.observationConfig().observationHandler(
            object : ObservationHandler<OutboxProcessObservationContext> {
                override fun onStop(context: OutboxProcessObservationContext) {
                    processContexts += context
                }

                override fun supportsContext(context: Observation.Context): Boolean =
                    context is OutboxProcessObservationContext
            },
        )
    }

    @Test
    fun `schedule creates documented context and invokes action once`() {
        val payload = Any()
        var actionInvocations = 0

        MicrometerOutboxInstrumentation(observationRegistry).schedule(
            invocation =
                OutboxScheduleInvocation(
                    payload = payload,
                    recordKey = "order-42",
                    channel = "orders",
                ),
            action = {
                actionInvocations++
            },
        )

        val context = scheduleContexts.single()
        assertThat(actionInvocations).isEqualTo(1)
        assertThat(context.name).isEqualTo(OutboxMetricNames.RECORD_SCHEDULE)
        assertThat(context.payloadType).isEqualTo("Any")
        assertThat(context.recordKey).isEqualTo("order-42")
        assertThat(context.channel).isEqualTo("orders")
        assertThat(context.lowCardinalityValue(OutboxMetricKeyNames.LowCardinality.CHANNEL)).isEqualTo("orders")
    }

    @Test
    fun `process maps primary and fallback handler kinds`() {
        val instrumentation = MicrometerOutboxInstrumentation(observationRegistry)
        val record = outboxRecord()

        instrumentation.process(OutboxProcessInvocation(record, PRIMARY, "orders")) {}
        instrumentation.process(OutboxProcessInvocation(record, FALLBACK, "orders")) {}

        assertThat(processContexts.map { it.getHandlerKind() })
            .containsExactly(HandlerKind.PRIMARY, HandlerKind.FALLBACK)
        assertThat(processContexts.map { it.getChannel() }).containsOnly("orders")
        assertThat(processContexts.map { it.name }).containsOnly(OutboxMetricNames.RECORD_PROCESS)
    }

    @Test
    fun `custom conventions remain effective`() {
        val instrumentation =
            MicrometerOutboxInstrumentation(
                observationRegistry = observationRegistry,
                customScheduleConventionSupplier =
                    {
                        object : OutboxScheduleObservationConvention {
                            override fun getName(): String = "custom.schedule"
                        }
                    },
                customProcessConventionSupplier =
                    {
                        object : OutboxProcessObservationConvention {
                            override fun getName(): String = "custom.process"
                        }
                    },
            )

        instrumentation.schedule(OutboxScheduleInvocation(Any(), "order-42", "orders")) {}
        instrumentation.process(OutboxProcessInvocation(outboxRecord(), PRIMARY, "orders")) {}

        assertThat(scheduleContexts.single().name).isEqualTo("custom.schedule")
        assertThat(processContexts.single().name).isEqualTo("custom.process")
    }

    @Test
    fun `custom convention suppliers resolve lazily once`() {
        val scheduleResolutions = AtomicInteger()
        val processResolutions = AtomicInteger()
        val instrumentation =
            MicrometerOutboxInstrumentation(
                observationRegistry = observationRegistry,
                customScheduleConventionSupplier = {
                    scheduleResolutions.incrementAndGet()
                    null
                },
                customProcessConventionSupplier = {
                    processResolutions.incrementAndGet()
                    null
                },
            )

        assertThat(scheduleResolutions).hasValue(0)
        assertThat(processResolutions).hasValue(0)

        instrumentation.schedule(OutboxScheduleInvocation(Any(), "order-1", "orders")) {}
        instrumentation.schedule(OutboxScheduleInvocation(Any(), "order-2", "orders")) {}
        instrumentation.process(OutboxProcessInvocation(outboxRecord(), PRIMARY, "orders")) {}
        instrumentation.process(OutboxProcessInvocation(outboxRecord(), PRIMARY, "orders")) {}

        assertThat(scheduleResolutions).hasValue(1)
        assertThat(processResolutions).hasValue(1)
    }

    @Test
    fun `action error is recorded and rethrown`() {
        val failure = IllegalStateException("handler failed")

        assertThatThrownBy {
            MicrometerOutboxInstrumentation(observationRegistry).process(
                OutboxProcessInvocation(outboxRecord(), PRIMARY, "orders"),
            ) {
                throw failure
            }
        }.isSameAs(failure)
        assertThat(processContexts.single().error).isSameAs(failure)
    }

    @Test
    fun `process context retains stored trace propagation carrier`() {
        val record = outboxRecord(context = mapOf("traceparent" to "stored-trace-context"))

        MicrometerOutboxInstrumentation(observationRegistry).process(
            OutboxProcessInvocation(record, PRIMARY, "orders"),
        ) {}

        val context = processContexts.single()
        assertThat(context.carrier).isSameAs(record)
        assertThat(context.getter.get(record, "traceparent")).isEqualTo("stored-trace-context")
    }

    private fun outboxRecord(context: Map<String, String> = emptyMap()): OutboxRecord<Any> =
        OutboxRecord
            .Builder<Any>()
            .key("order-42")
            .payload(Any())
            .context(context)
            .handlerId("order-handler")
            .build(Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC))

    private fun Observation.Context.lowCardinalityValue(key: String): String? = getLowCardinalityKeyValue(key)?.value
}
