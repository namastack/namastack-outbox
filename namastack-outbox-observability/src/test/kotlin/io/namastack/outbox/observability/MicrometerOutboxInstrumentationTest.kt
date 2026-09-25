package io.namastack.outbox.observability

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import io.namastack.outbox.OutboxHandlerNotFoundException
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordStatus
import io.namastack.outbox.instrumentation.OutboxHandlerInvocation
import io.namastack.outbox.instrumentation.OutboxHandlerKind.FALLBACK
import io.namastack.outbox.instrumentation.OutboxHandlerKind.PRIMARY
import io.namastack.outbox.instrumentation.OutboxRecordProcessingInvocation
import io.namastack.outbox.instrumentation.OutboxRecordProcessingOutcome
import io.namastack.outbox.instrumentation.OutboxScheduleInvocation
import io.namastack.outbox.observability.OutboxHandlerObservationContext.HandlerKind
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class MicrometerOutboxInstrumentationTest {
    private val observationRegistry = ObservationRegistry.create()
    private val scheduleContexts = mutableListOf<OutboxScheduleObservationContext>()
    private val recordProcessingContexts = mutableListOf<OutboxRecordProcessingObservationContext>()
    private val handlerContexts = mutableListOf<OutboxHandlerObservationContext>()

    init {
        observationRegistry.observationConfig().observationHandler(
            object : ObservationHandler<OutboxRecordProcessingObservationContext> {
                override fun onStop(context: OutboxRecordProcessingObservationContext) {
                    recordProcessingContexts += context
                }

                override fun supportsContext(context: Observation.Context): Boolean =
                    context is OutboxRecordProcessingObservationContext
            },
        )
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
            object : ObservationHandler<OutboxHandlerObservationContext> {
                override fun onStop(context: OutboxHandlerObservationContext) {
                    handlerContexts += context
                }

                override fun supportsContext(context: Observation.Context): Boolean =
                    context is OutboxHandlerObservationContext
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

        instrumentation.invokeHandler(OutboxHandlerInvocation(record, PRIMARY, "orders")) {}
        instrumentation.invokeHandler(OutboxHandlerInvocation(record, FALLBACK, "orders")) {}

        assertThat(handlerContexts.map { it.getHandlerKind() })
            .containsExactly(HandlerKind.PRIMARY, HandlerKind.FALLBACK)
        assertThat(handlerContexts.map { it.getChannel() }).containsOnly("orders")
        assertThat(handlerContexts.map { it.name }).containsOnly(OutboxMetricNames.RECORD_PROCESS)
    }

    @Test
    fun `record processing records and returns normal outcome`() {
        val actual =
            MicrometerOutboxInstrumentation(observationRegistry).processRecord(
                OutboxRecordProcessingInvocation(outboxRecord(failureCount = 1), "orders"),
            ) {
                OutboxRecordProcessingOutcome.RETRY_SCHEDULED
            }

        val context = recordProcessingContexts.single()
        assertThat(actual).isEqualTo(OutboxRecordProcessingOutcome.RETRY_SCHEDULED)
        assertThat(context.name).isEqualTo(OutboxMetricNames.RECORD_ATTEMPT)
        assertThat(context.getOutcome()).isEqualTo(OutboxRecordProcessingOutcome.RETRY_SCHEDULED)
        assertThat(context.getDeliveryAttempt()).isEqualTo(2)
        assertThat(context.lowCardinalityValue(OutboxMetricKeyNames.LowCardinality.PROCESSING_OUTCOME))
            .isEqualTo("retry_scheduled")
    }

    @Test
    fun `record processing tags every normal Core outcome`() {
        val instrumentation = MicrometerOutboxInstrumentation(observationRegistry)

        OutboxRecordProcessingOutcome.entries.forEach { outcome ->
            instrumentation.processRecord(
                OutboxRecordProcessingInvocation(outboxRecord(), "orders"),
            ) {
                outcome
            }
        }

        assertThat(
            recordProcessingContexts.map {
                it.lowCardinalityValue(OutboxMetricKeyNames.LowCardinality.PROCESSING_OUTCOME)
            },
        ).containsExactly("completed", "retry_scheduled", "failed")
    }

    @Test
    fun `handler observation is nested under record processing observation`() {
        val instrumentation = MicrometerOutboxInstrumentation(observationRegistry)
        val record = outboxRecord()

        instrumentation.processRecord(OutboxRecordProcessingInvocation(record, "orders")) {
            instrumentation.invokeHandler(OutboxHandlerInvocation(record, PRIMARY, "orders")) {}
            OutboxRecordProcessingOutcome.COMPLETED
        }

        assertThat(handlerContexts.single().parentObservation).isNotNull()
    }

    @Test
    fun `handler observation derives delivery attempt from record`() {
        val record = outboxRecord(failureCount = 2)

        MicrometerOutboxInstrumentation(observationRegistry).invokeHandler(
            OutboxHandlerInvocation(record, PRIMARY, "orders"),
        ) {}

        assertThat(handlerContexts.single().getDeliveryAttempt()).isEqualTo(3)
    }

    @Test
    fun `record processing records compatibility exception without creating a Core outcome`() {
        val failure =
            OutboxHandlerNotFoundException(
                recordId = "record-1",
                recordKey = "order-42",
                handlerId = "order-handler",
            )

        assertThatThrownBy {
            MicrometerOutboxInstrumentation(observationRegistry).processRecord(
                OutboxRecordProcessingInvocation(outboxRecord(), "orders"),
            ) {
                throw failure
            }
        }.isSameAs(failure)

        val context = recordProcessingContexts.single()
        assertThat(context.error).isSameAs(failure)
        assertThat(context.getOutcome()).isNull()
        assertThat(context.lowCardinalityValue(OutboxMetricKeyNames.LowCardinality.PROCESSING_OUTCOME))
            .isNull()
    }

    @Test
    fun `record processing records unexpected exception without creating a Core outcome`() {
        val failure = IllegalStateException("database unavailable")

        assertThatThrownBy {
            MicrometerOutboxInstrumentation(observationRegistry).processRecord(
                OutboxRecordProcessingInvocation(outboxRecord(), "orders"),
            ) {
                throw failure
            }
        }.isSameAs(failure)

        val context = recordProcessingContexts.single()
        assertThat(context.error).isSameAs(failure)
        assertThat(context.getOutcome()).isNull()
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
                customRecordProcessingConventionSupplier =
                    {
                        object : OutboxRecordProcessingObservationConvention {
                            override fun getName(): String = "custom.attempt"
                        }
                    },
                customHandlerConventionSupplier =
                    {
                        object : OutboxHandlerObservationConvention {
                            override fun getName(): String = "custom.process"
                        }
                    },
            )

        instrumentation.schedule(OutboxScheduleInvocation(Any(), "order-42", "orders")) {}
        instrumentation.processRecord(
            OutboxRecordProcessingInvocation(outboxRecord(), "orders"),
        ) {
            OutboxRecordProcessingOutcome.COMPLETED
        }
        instrumentation.invokeHandler(OutboxHandlerInvocation(outboxRecord(), PRIMARY, "orders")) {}

        assertThat(scheduleContexts.single().name).isEqualTo("custom.schedule")
        assertThat(recordProcessingContexts.single().name).isEqualTo("custom.attempt")
        assertThat(handlerContexts.single().name).isEqualTo("custom.process")
    }

    @Test
    fun `custom convention suppliers resolve lazily once`() {
        val scheduleResolutions = AtomicInteger()
        val recordProcessingResolutions = AtomicInteger()
        val processResolutions = AtomicInteger()
        val instrumentation =
            MicrometerOutboxInstrumentation(
                observationRegistry = observationRegistry,
                customScheduleConventionSupplier = {
                    scheduleResolutions.incrementAndGet()
                    null
                },
                customRecordProcessingConventionSupplier = {
                    recordProcessingResolutions.incrementAndGet()
                    null
                },
                customHandlerConventionSupplier = {
                    processResolutions.incrementAndGet()
                    null
                },
            )

        assertThat(scheduleResolutions).hasValue(0)
        assertThat(recordProcessingResolutions).hasValue(0)
        assertThat(processResolutions).hasValue(0)

        instrumentation.schedule(OutboxScheduleInvocation(Any(), "order-1", "orders")) {}
        instrumentation.schedule(OutboxScheduleInvocation(Any(), "order-2", "orders")) {}
        repeat(2) {
            instrumentation.processRecord(
                OutboxRecordProcessingInvocation(outboxRecord(), "orders"),
            ) {
                OutboxRecordProcessingOutcome.COMPLETED
            }
        }
        instrumentation.invokeHandler(OutboxHandlerInvocation(outboxRecord(), PRIMARY, "orders")) {}
        instrumentation.invokeHandler(OutboxHandlerInvocation(outboxRecord(), PRIMARY, "orders")) {}

        assertThat(scheduleResolutions).hasValue(1)
        assertThat(recordProcessingResolutions).hasValue(1)
        assertThat(processResolutions).hasValue(1)
    }

    @Test
    fun `observation registry supplier resolves lazily once`() {
        val registryResolutions = AtomicInteger()
        val instrumentation =
            MicrometerOutboxInstrumentation(
                observationRegistrySupplier = {
                    registryResolutions.incrementAndGet()
                    observationRegistry
                },
            )

        assertThat(registryResolutions).hasValue(0)

        instrumentation.schedule(OutboxScheduleInvocation(Any(), "order-1", "orders")) {}
        instrumentation.schedule(OutboxScheduleInvocation(Any(), "order-2", "orders")) {}
        instrumentation.invokeHandler(OutboxHandlerInvocation(outboxRecord(), PRIMARY, "orders")) {}

        assertThat(registryResolutions).hasValue(1)
    }

    @Test
    fun `action error is recorded and rethrown`() {
        val failure = IllegalStateException("handler failed")

        assertThatThrownBy {
            MicrometerOutboxInstrumentation(observationRegistry).invokeHandler(
                OutboxHandlerInvocation(outboxRecord(), PRIMARY, "orders"),
            ) {
                throw failure
            }
        }.isSameAs(failure)
        assertThat(handlerContexts.single().error).isSameAs(failure)
    }

    @Test
    fun `process context retains stored trace propagation carrier`() {
        val record = outboxRecord(context = mapOf("traceparent" to "stored-trace-context"))

        MicrometerOutboxInstrumentation(observationRegistry).processRecord(
            OutboxRecordProcessingInvocation(record, "orders"),
        ) {
            OutboxRecordProcessingOutcome.COMPLETED
        }

        val context = recordProcessingContexts.single()
        assertThat(context.carrier).isSameAs(record)
        assertThat(context.getter.get(record, "traceparent")).isEqualTo("stored-trace-context")
    }

    private fun outboxRecord(
        context: Map<String, String> = emptyMap(),
        failureCount: Int = 0,
    ): OutboxRecord<Any> {
        val now = Instant.parse("2025-01-01T00:00:00Z")
        return OutboxRecord.restore(
            id = "record-1",
            recordKey = "order-42",
            payload = Any(),
            context = context,
            createdAt = now,
            status = OutboxRecordStatus.NEW,
            completedAt = null,
            failureCount = failureCount,
            failureException = null,
            failureReason = null,
            partition = 1,
            nextRetryAt = now,
            handlerId = "order-handler",
        )
    }

    private fun Observation.Context.lowCardinalityValue(key: String): String? = getLowCardinalityKeyValue(key)?.value
}
