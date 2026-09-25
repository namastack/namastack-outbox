package io.namastack.outbox.observability

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import io.mockk.every
import io.mockk.mockk
import io.namastack.outbox.Outbox
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.handler.OutboxHandlerIdentity
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.OutboxTypedHandler
import io.namastack.outbox.instance.OutboxInstance
import io.namastack.outbox.instance.OutboxInstanceRepository
import io.namastack.outbox.instrumentation.OutboxHandlerInvocation
import io.namastack.outbox.instrumentation.OutboxInstrumentation
import io.namastack.outbox.instrumentation.OutboxRecordProcessingInvocation
import io.namastack.outbox.instrumentation.OutboxRecordProcessingOutcome
import io.namastack.outbox.instrumentation.OutboxScheduleInvocation
import io.namastack.outbox.partition.PartitionAssignmentRepository
import io.namastack.outbox.processor.OutboxRecordProcessorChainInvoker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.scheduling.TaskScheduler
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class OutboxObservabilityCoreIntegrationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    OutboxObservabilityAutoConfiguration::class.java,
                    io.namastack.outbox.config.OutboxCoreInfrastructureAutoConfiguration::class.java,
                    io.namastack.outbox.config.OutboxCoreProcessingAutoConfiguration::class.java,
                ),
            ).withUserConfiguration(TestConfiguration::class.java)
            .withPropertyValues("namastack.outbox.instance.graceful-shutdown-timeout=1ms")

    @Test
    fun `Core boundaries invoke user and Micrometer instrumentation exactly once`() {
        TestConfiguration.events.clear()
        TestConfiguration.scheduleContexts.clear()
        TestConfiguration.recordProcessingContexts.clear()
        TestConfiguration.handlerContexts.clear()

        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBeansOfType(OutboxInstrumentation::class.java)).hasSize(2)
            assertThat(context).doesNotHaveBean("outboxObservabilityHandlerAdvisor")
            assertThat(context).doesNotHaveBean("outboxObservabilityFallbackAdvisor")
            assertThat(context).doesNotHaveBean("outboxObservabilityScheduleAdvisor")

            context.getBean<Outbox>().schedule(IntegrationPayload("created"), "order-1")
            context.getBean<OutboxRecordProcessorChainInvoker>().process(outboxRecord())

            assertThat(TestConfiguration.scheduleContexts).hasSize(1)
            assertThat(TestConfiguration.scheduleContexts.single().recordKey).isEqualTo("order-1")
            assertThat(TestConfiguration.recordProcessingContexts).hasSize(1)
            assertThat(TestConfiguration.recordProcessingContexts.single().getOutcome())
                .isEqualTo(OutboxRecordProcessingOutcome.COMPLETED)
            assertThat(TestConfiguration.handlerContexts).hasSize(1)
            assertThat(TestConfiguration.handlerContexts.single().getHandlerId())
                .isEqualTo("integration-handler")
            assertThat(TestConfiguration.events)
                .containsExactly(
                    "user.schedule.before",
                    "user.schedule.after",
                    "user.record.before",
                    "user.process.before",
                    "handler",
                    "user.process.after",
                    "user.record.after",
                )
        }
    }

    @Test
    fun `custom convention can depend on Outbox without a startup cycle`() {
        TestConfiguration.scheduleContexts.clear()

        contextRunner
            .withUserConfiguration(OutboxDependentConventionConfiguration::class.java)
            .run { context ->
                assertThat(context).hasNotFailed()

                context.getBean<Outbox>().schedule(IntegrationPayload("created"), "order-1")

                assertThat(TestConfiguration.scheduleContexts.single().name).isEqualTo("custom.schedule")
            }
    }

    @Test
    fun `custom observation registry can depend on Outbox without a startup cycle`() {
        TestConfiguration.scheduleContexts.clear()

        contextRunner.run { context ->
            assertThat(context).hasNotFailed()

            context.getBean<Outbox>().schedule(IntegrationPayload("created"), "order-1")

            assertThat(TestConfiguration.scheduleContexts).hasSize(1)
        }
    }

    private fun outboxRecord(): OutboxRecord<IntegrationPayload> =
        OutboxRecord
            .Builder<IntegrationPayload>()
            .key("order-1")
            .payload(IntegrationPayload("created"))
            .handlerId("integration-handler")
            .build(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))

    @Configuration
    class TestConfiguration {
        @Bean
        fun observationRegistry(outbox: Outbox): ObservationRegistry =
            ObservationRegistry.create().apply {
                observationConfig().observationHandler(
                    object : ObservationHandler<OutboxRecordProcessingObservationContext> {
                        override fun onStop(context: OutboxRecordProcessingObservationContext) {
                            recordProcessingContexts += context
                        }

                        override fun supportsContext(context: Observation.Context): Boolean =
                            context is OutboxRecordProcessingObservationContext
                    },
                )
                observationConfig().observationHandler(
                    object : ObservationHandler<OutboxScheduleObservationContext> {
                        override fun onStop(context: OutboxScheduleObservationContext) {
                            scheduleContexts += context
                        }

                        override fun supportsContext(context: Observation.Context): Boolean =
                            context is OutboxScheduleObservationContext
                    },
                )
                observationConfig().observationHandler(
                    object : ObservationHandler<OutboxHandlerObservationContext> {
                        override fun onStop(context: OutboxHandlerObservationContext) {
                            handlerContexts += context
                        }

                        override fun supportsContext(context: Observation.Context): Boolean =
                            context is OutboxHandlerObservationContext
                    },
                )
            }

        @Bean
        fun userInstrumentation(): OutboxInstrumentation =
            object : OutboxInstrumentation, Ordered {
                override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

                override fun schedule(
                    invocation: OutboxScheduleInvocation,
                    action: () -> Unit,
                ) {
                    events += "user.schedule.before"
                    try {
                        action()
                    } finally {
                        events += "user.schedule.after"
                    }
                }

                override fun invokeHandler(
                    invocation: OutboxHandlerInvocation,
                    action: () -> Unit,
                ) {
                    events += "user.process.before"
                    try {
                        action()
                    } finally {
                        events += "user.process.after"
                    }
                }

                override fun processRecord(
                    invocation: OutboxRecordProcessingInvocation,
                    action: () -> OutboxRecordProcessingOutcome,
                ): OutboxRecordProcessingOutcome {
                    events += "user.record.before"
                    return try {
                        action()
                    } finally {
                        events += "user.record.after"
                    }
                }
            }

        @Bean
        fun integrationHandler(): OutboxTypedHandler<IntegrationPayload> =
            object : OutboxTypedHandler<IntegrationPayload> {
                override fun getTypedHandlerIdentity() = OutboxHandlerIdentity("integration-handler")

                override fun handle(
                    payload: IntegrationPayload,
                    metadata: OutboxRecordMetadata,
                ) {
                    events += "handler"
                }
            }

        @Bean
        fun outboxRecordRepository() = mockk<OutboxRecordRepository>(relaxed = true)

        @Bean
        fun partitionAssignmentRepository() = mockk<PartitionAssignmentRepository>(relaxed = true)

        @Bean
        fun outboxInstanceRepository() =
            mockk<OutboxInstanceRepository>(relaxed = true).apply {
                every { findActiveInstances() } returns listOf(mockk<OutboxInstance>(relaxed = true))
            }

        @Bean("outboxHeartbeatScheduler")
        fun outboxHeartbeatScheduler() = mockk<TaskScheduler>(relaxed = true)

        companion object {
            val events = mutableListOf<String>()
            val scheduleContexts = mutableListOf<OutboxScheduleObservationContext>()
            val recordProcessingContexts = mutableListOf<OutboxRecordProcessingObservationContext>()
            val handlerContexts = mutableListOf<OutboxHandlerObservationContext>()
        }
    }

    data class IntegrationPayload(
        val state: String,
    )
}
