package io.namastack.outbox.runtime

import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.OutboxProperties
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.annotation.OutboxHandler
import io.namastack.outbox.context.OutboxContextCollector
import io.namastack.outbox.handler.OutboxHandlerInfrastructure
import io.namastack.outbox.handler.OutboxHandlerInfrastructureFactory
import io.namastack.outbox.instance.OutboxInstanceRepository
import io.namastack.outbox.instrumentation.OutboxInstrumentation
import io.namastack.outbox.instrumentation.OutboxScheduleInvocation
import io.namastack.outbox.partition.PartitionAssignmentRepository
import io.namastack.outbox.retry.OutboxRetryPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.scheduling.TaskScheduler
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class OutboxRuntimeFactoryTest {
    @Test
    fun `creates separate stopped runtimes backed by their own persistence`() {
        val orders = fixture("orders")
        val payments = fixture("payments")

        val ordersRuntime = OutboxRuntimeFactory.create(orders.spec)
        val paymentsRuntime = OutboxRuntimeFactory.create(payments.spec)

        assertThat(ordersRuntime.isRunning()).isFalse()
        assertThat(paymentsRuntime.isRunning()).isFalse()
        assertThat(ordersRuntime.outbox).isNotSameAs(paymentsRuntime.outbox)
        assertThat(orders.handlerInfrastructure.handlerRegistry)
            .isNotSameAs(payments.handlerInfrastructure.handlerRegistry)

        ordersRuntime.outbox.schedule(RuntimePayload("created"), "order-1")

        verify(exactly = 1) { orders.recordRepository.save(any<OutboxRecord<Any>>()) }
        verify(exactly = 0) { payments.recordRepository.save(any<OutboxRecord<Any>>()) }
        verify(exactly = 0) { orders.instanceRepository.save(any()) }
    }

    @Test
    fun `uses handler infrastructure instrumentation and channel for scheduling`() {
        val orders = fixture("orders")
        val runtime = OutboxRuntimeFactory.create(orders.spec)

        runtime.outbox.schedule(RuntimePayload("created"), "order-1")

        assertThat(orders.instrumentation.channels).containsExactly("orders")
    }

    private fun fixture(channel: String): RuntimeFixture {
        val instrumentation = RecordingInstrumentation()
        val handlerInfrastructure =
            OutboxHandlerInfrastructureFactory(
                beanFactory = DefaultListableBeanFactory(),
                instrumentationsSupplier = { listOf(instrumentation) },
            ).create(
                defaultRetryPolicy = OutboxRetryPolicy.builder().build(),
                channelName = channel,
            )
        handlerInfrastructure.register(RuntimeHandler(), "runtimeHandler")

        val recordRepository = mockk<OutboxRecordRepository>(relaxed = true)
        val instanceRepository = mockk<OutboxInstanceRepository>(relaxed = true)
        val spec =
            OutboxRuntimeSpec(
                properties = OutboxProperties(),
                persistence =
                    OutboxRuntimePersistence(
                        recordRepository = recordRepository,
                        instanceRepository = instanceRepository,
                        partitionAssignmentRepository = mockk<PartitionAssignmentRepository>(relaxed = true),
                    ),
                handlerInfrastructure = handlerInfrastructure,
                contextCollector = OutboxContextCollector(emptyList()),
                resources =
                    OutboxRuntimeResources(
                        taskExecutor = SyncTaskExecutor(),
                        taskScheduler = mockk<TaskScheduler>(relaxed = true),
                        heartbeatScheduler = mockk<TaskScheduler>(relaxed = true),
                    ),
                clock = CLOCK,
            )

        return RuntimeFixture(
            spec = spec,
            recordRepository = recordRepository,
            instanceRepository = instanceRepository,
            handlerInfrastructure = handlerInfrastructure,
            instrumentation = instrumentation,
        )
    }

    private data class RuntimeFixture(
        val spec: OutboxRuntimeSpec,
        val recordRepository: OutboxRecordRepository,
        val instanceRepository: OutboxInstanceRepository,
        val handlerInfrastructure: OutboxHandlerInfrastructure,
        val instrumentation: RecordingInstrumentation,
    )

    private class RecordingInstrumentation : OutboxInstrumentation {
        val channels = mutableListOf<String>()

        override fun schedule(
            invocation: OutboxScheduleInvocation,
            action: () -> Unit,
        ) {
            channels += invocation.channel
            action()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private class RuntimeHandler {
        @OutboxHandler(id = "runtime-handler")
        fun handle(payload: RuntimePayload) = Unit
    }

    private data class RuntimePayload(
        val state: String,
    )

    private companion object {
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    }
}
