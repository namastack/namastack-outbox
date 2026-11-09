package io.namastack.outbox.runtime

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordPage
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.annotation.OutboxHandler
import io.namastack.outbox.completion.OutboxCompletionWriter
import io.namastack.outbox.context.OutboxContextCollector
import io.namastack.outbox.handler.OutboxHandlerInfrastructure
import io.namastack.outbox.handler.OutboxHandlerInfrastructureFactory
import io.namastack.outbox.instance.OutboxInstance
import io.namastack.outbox.instance.OutboxInstanceRepository
import io.namastack.outbox.instrumentation.OutboxInstrumentation
import io.namastack.outbox.instrumentation.OutboxScheduleInvocation
import io.namastack.outbox.partition.PartitionAssignment
import io.namastack.outbox.partition.PartitionAssignmentRepository
import io.namastack.outbox.processing.OutboxProcessingGroupSettings
import io.namastack.outbox.retry.OutboxRetryPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.Trigger
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class OutboxRuntimeFactoryTest {
    private val completionExecutors = mutableListOf<ThreadPoolTaskExecutor>()

    @BeforeEach
    fun setUpTransactionState() {
        TransactionSynchronizationManager.setActualTransactionActive(true)
    }

    @AfterEach
    fun clearTransactionState() {
        TransactionSynchronizationManager.clear()
        completionExecutors.forEach { it.shutdown() }
    }

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

        verify(exactly = 1) { orders.recordRepository.create(any<OutboxRecord<Any>>()) }
        verify(exactly = 0) { payments.recordRepository.create(any<OutboxRecord<Any>>()) }
        verify(exactly = 0) { orders.instanceRepository.save(any()) }
    }

    @Test
    fun `uses handler infrastructure instrumentation and channel for scheduling`() {
        val orders = fixture("orders")
        val runtime = OutboxRuntimeFactory.create(orders.spec)

        runtime.outbox.schedule(RuntimePayload("created"), "order-1")

        assertThat(orders.instrumentation.channels).containsExactly("orders")
    }

    @Test
    fun `applies processing group settings independently to each runtime`() {
        val orders = fixture("orders")
        val payments = fixture("payments")
        val settings =
            OutboxRuntimeSettings(
                processingGroups = OutboxProcessingGroupSettings(enabled = true),
            )
        val ordersRuntime = OutboxRuntimeFactory.create(orders.spec.copy(settings = settings))
        val paymentsRuntime = OutboxRuntimeFactory.create(payments.spec)

        ordersRuntime.outbox.schedule(RuntimePayload("created"), "order-1")
        paymentsRuntime.outbox.schedule(RuntimePayload("created"), "order-1")

        verify(exactly = 1) {
            orders.recordRepository.create(match<OutboxRecord<Any>> { it.key == "~ns.pg1~billing:order-1" })
        }
        verify(exactly = 1) {
            payments.recordRepository.create(match<OutboxRecord<Any>> { it.key == "order-1" })
        }
    }

    @Test
    fun `batching without a storage writer fails before startup`() {
        val orders = fixture("orders")
        assertThatThrownBy {
            OutboxRuntimeFactory.create(
                orders.spec.copy(
                    settings =
                        OutboxRuntimeSettings(
                            completionBatching = OutboxRuntimeSettings.CompletionBatching(enabled = true),
                        ),
                ),
            )
        }.hasMessageContaining("requires a completion writer")
        verify(exactly = 0) { orders.instanceRepository.save(any()) }
    }

    @Test
    fun `batching without a completion executor fails before startup`() {
        val orders = fixture("orders")
        assertThatThrownBy {
            OutboxRuntimeFactory.create(
                orders.spec.copy(
                    settings =
                        OutboxRuntimeSettings(
                            completionBatching = OutboxRuntimeSettings.CompletionBatching(enabled = true),
                        ),
                    persistence = orders.spec.persistence.copy(completionWriter = mockk()),
                    resources = orders.spec.resources.copy(completionExecutor = null),
                ),
            )
        }.hasMessageContaining("requires a completion executor")
        verify(exactly = 0) { orders.instanceRepository.save(any()) }
    }

    @Test
    fun `runtime assembles batching and processes completions through its writer`() {
        val orders = fixture("orders")
        val writer = mockk<OutboxCompletionWriter>()
        every { writer.writeDeletions(any()) } answers { firstArg<List<Any>>().map { Result.success(Unit) } }
        val spec =
            orders.spec.copy(
                persistence = orders.spec.persistence.copy(completionWriter = writer),
                settings =
                    orders.spec.settings.copy(
                        processing = OutboxRuntimeSettings.Processing(deleteCompletedRecords = true),
                        completionBatching = OutboxRuntimeSettings.CompletionBatching(enabled = true),
                    ),
            )
        val poll = prepareProcessing(spec)
        val runtime = OutboxRuntimeFactory.create(spec)
        try {
            runtime.start()
            TransactionSynchronizationManager.setActualTransactionActive(false)
            poll.captured.run()
            verify(exactly = 1) { writer.writeDeletions(match { it.size == 1 }) }
            verify(exactly = 0) { orders.recordRepository.deleteById(any()) }
        } finally {
            runtime.close()
        }
        assertThat(
            (spec.resources.completionExecutor as ThreadPoolTaskExecutor).threadPoolExecutor.isShutdown,
        ).isFalse()
    }

    @Test
    fun `copying a spec uses the replacement repository for completion`() {
        val orders = fixture("orders")
        val payments = fixture("payments")
        val spec =
            orders.spec.copy(
                persistence = payments.spec.persistence,
                settings =
                    orders.spec.settings.copy(
                        processing = OutboxRuntimeSettings.Processing(deleteCompletedRecords = true),
                    ),
            )
        val poll = prepareProcessing(spec)
        val runtime = OutboxRuntimeFactory.create(spec)
        try {
            runtime.start()
            TransactionSynchronizationManager.setActualTransactionActive(false)
            poll.captured.run()
            verify(exactly = 1) { payments.recordRepository.deleteById(any()) }
            verify(exactly = 0) { orders.recordRepository.deleteById(any()) }
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `processing startup failure closes the runtime with batching enabled`() {
        val orders = fixture("orders")
        val writer = mockk<OutboxCompletionWriter>()
        val spec = orders.spec.copy(persistence = orders.spec.persistence.copy(completionWriter = writer))
        prepareProcessing(spec)
        every { spec.resources.taskScheduler.schedule(any<Runnable>(), any<Trigger>()) } throws
            IllegalStateException("polling rejected")
        val runtime =
            OutboxRuntimeFactory.create(
                spec.copy(
                    settings =
                        spec.settings.copy(
                            completionBatching = OutboxRuntimeSettings.CompletionBatching(enabled = true),
                        ),
                ),
            )
        assertThatThrownBy { runtime.start() }.hasMessage("polling rejected")
        assertThat(runtime.isRunning()).isFalse()
        assertThatThrownBy { runtime.start() }.hasMessage("Outbox runtime is already closed")
        runtime.close()
    }

    private fun prepareProcessing(spec: OutboxRuntimeSpec): io.mockk.CapturingSlot<Runnable> {
        val registered = slot<OutboxInstance>()
        every { spec.persistence.instanceRepository.save(capture(registered)) } answers { firstArg() }
        every { spec.persistence.instanceRepository.findActiveInstances() } answers { listOf(registered.captured) }
        every { spec.persistence.partitionAssignmentRepository.findByInstanceId(any()) } answers {
            setOf(PartitionAssignment.create(1, registered.captured.instanceId, CLOCK, null))
        }
        val record =
            OutboxRecord
                .Builder<RuntimePayload>()
                .key("key")
                .payload(RuntimePayload("test"))
                .handlerId("runtime-handler")
                .build(CLOCK)
        val page = mockk<OutboxRecordPage>()
        every { page.isEmpty() } returns false
        every { page.size } returns 1
        every { page[0] } returns record
        every { spec.persistence.recordRepository.findRecordKeysInPartitions(any(), any(), any()) } returns
            listOf("key")
        every { spec.persistence.recordRepository.findIncompleteRecordPages(any()) } returns mapOf("key" to page)
        val poll = slot<Runnable>()
        every { spec.resources.taskScheduler.schedule(capture(poll), any<Trigger>()) } returns mockk(relaxed = true)
        return poll
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
                settings = OutboxRuntimeSettings(),
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
                        completionExecutor =
                            ThreadPoolTaskExecutor().apply {
                                initialize()
                                completionExecutors += this
                            },
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
        @OutboxHandler(id = "runtime-handler", processingGroup = "billing")
        fun handle(payload: RuntimePayload) = Unit
    }

    private data class RuntimePayload(
        val state: String,
    )

    private companion object {
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
    }
}
