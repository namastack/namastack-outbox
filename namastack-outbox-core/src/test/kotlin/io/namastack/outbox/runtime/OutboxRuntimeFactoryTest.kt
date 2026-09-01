package io.namastack.outbox.runtime

import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.OutboxChannelNameProvider
import io.namastack.outbox.OutboxProperties
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxRecordStatus
import io.namastack.outbox.annotation.OutboxHandler
import io.namastack.outbox.context.OutboxContextCollector
import io.namastack.outbox.handler.OutboxHandlerInfrastructure
import io.namastack.outbox.instance.OutboxInstanceRepository
import io.namastack.outbox.instrumentation.OutboxInstrumentation
import io.namastack.outbox.instrumentation.OutboxProcessInvocation
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
    fun `creates isolated component graphs for separate specifications`() {
        val first = fixture("orders")
        val second = fixture("billing")

        val firstRuntime = OutboxRuntimeFactory.create(first.spec)
        val secondRuntime = OutboxRuntimeFactory.create(second.spec)

        assertThat(firstRuntime).isNotSameAs(secondRuntime)
        assertThat(firstRuntime.outbox).isNotSameAs(secondRuntime.outbox)
        assertThat(first.handlerInfrastructure.handlerRegistry)
            .isNotSameAs(second.handlerInfrastructure.handlerRegistry)

        firstRuntime.outbox.schedule(RuntimePayload("created"), "order-1")

        verify(exactly = 1) { first.recordRepository.save(any<OutboxRecord<Any>>()) }
        verify(exactly = 0) { second.recordRepository.save(any<OutboxRecord<Any>>()) }
    }

    @Test
    fun `uses supplied channel for schedule and handler instrumentation`() {
        val fixture = fixture("orders")
        val runtime = OutboxRuntimeFactory.create(fixture.spec)
        val payload = RuntimePayload("created")

        runtime.outbox.schedule(payload, "order-1")
        fixture.handlerInfrastructure.handlerInvoker.dispatch(record(payload))

        assertThat(fixture.instrumentation.scheduleChannels).containsExactly("orders")
        assertThat(fixture.instrumentation.processChannels).containsExactly("orders")
    }

    private fun fixture(channel: String): RuntimeFixture {
        val instrumentation = RecordingInstrumentation()
        val channelNameProvider = OutboxChannelNameProvider { channel }
        val beanFactory = DefaultListableBeanFactory()
        val handlerInfrastructure =
            OutboxHandlerInfrastructure(
                beanFactory = beanFactory,
                defaultRetryPolicy = OutboxRetryPolicy.builder().build(),
                instrumentation = instrumentation,
                channelNameProvider = channelNameProvider,
            )
        handlerInfrastructure.register(RuntimeHandler(), "runtimeHandler")

        val recordRepository = mockk<OutboxRecordRepository>(relaxed = true)
        val persistence =
            OutboxRuntimePersistence(
                recordRepository = recordRepository,
                instanceRepository = mockk<OutboxInstanceRepository>(relaxed = true),
                partitionAssignmentRepository = mockk<PartitionAssignmentRepository>(relaxed = true),
            )
        val resources =
            OutboxRuntimeResources(
                taskExecutor = SyncTaskExecutor(),
                taskScheduler = mockk<TaskScheduler>(relaxed = true),
                heartbeatScheduler = mockk<TaskScheduler>(relaxed = true),
            )
        val spec =
            OutboxRuntimeSpec(
                properties = OutboxProperties(),
                persistence = persistence,
                handlerInfrastructure = handlerInfrastructure,
                contextCollector = OutboxContextCollector(emptyList()),
                resources = resources,
                clock = CLOCK,
            )

        return RuntimeFixture(spec, recordRepository, handlerInfrastructure, instrumentation)
    }

    private fun record(payload: RuntimePayload): OutboxRecord<RuntimePayload> =
        OutboxRecord.restore(
            id = "record-1",
            recordKey = "order-1",
            payload = payload,
            context = emptyMap(),
            createdAt = CLOCK.instant(),
            status = OutboxRecordStatus.NEW,
            completedAt = null,
            failureCount = 0,
            failureException = null,
            failureReason = null,
            partition = 1,
            nextRetryAt = CLOCK.instant(),
            handlerId = "runtime-handler",
        )

    private data class RuntimeFixture(
        val spec: OutboxRuntimeSpec,
        val recordRepository: OutboxRecordRepository,
        val handlerInfrastructure: OutboxHandlerInfrastructure,
        val instrumentation: RecordingInstrumentation,
    )

    private class RecordingInstrumentation : OutboxInstrumentation {
        val scheduleChannels = mutableListOf<String>()
        val processChannels = mutableListOf<String>()

        override fun schedule(
            invocation: OutboxScheduleInvocation,
            action: () -> Unit,
        ) {
            scheduleChannels += invocation.channel
            action()
        }

        override fun process(
            invocation: OutboxProcessInvocation,
            action: () -> Unit,
        ) {
            processChannels += invocation.channel
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
