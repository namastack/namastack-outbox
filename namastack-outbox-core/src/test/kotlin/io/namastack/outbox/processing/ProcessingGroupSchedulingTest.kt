package io.namastack.outbox.processing

import io.mockk.every
import io.mockk.mockk
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxService
import io.namastack.outbox.context.OutboxContextCollector
import io.namastack.outbox.context.OutboxContextProvider
import io.namastack.outbox.handler.OutboxHandler
import io.namastack.outbox.handler.OutboxHandlerIdentity
import io.namastack.outbox.handler.OutboxHandlerInfrastructureFactory
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.OutboxTypedHandler
import io.namastack.outbox.instrumentation.OutboxInstrumentation
import io.namastack.outbox.instrumentation.OutboxScheduleInvocation
import io.namastack.outbox.partition.PartitionHasher
import io.namastack.outbox.retry.OutboxRetryPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import io.namastack.outbox.annotation.OutboxHandler as OutboxHandlerAnnotation

class ProcessingGroupSchedulingTest {
    private val records = mutableListOf<OutboxRecord<Any>>()
    private val selectionMetadata = mutableListOf<OutboxRecordMetadata>()
    private val scheduleInvocations = mutableListOf<OutboxScheduleInvocation>()
    private var genericSupports = true
    private val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)

    @BeforeEach
    fun setUpTransactionState() {
        TransactionSynchronizationManager.setActualTransactionActive(true)
    }

    @AfterEach
    fun clearTransactionState() {
        TransactionSynchronizationManager.clear()
    }

    @Test
    fun `processing groups are disabled by default despite handler declarations`() {
        service().schedule("event", "order:42")

        assertThat(records).hasSize(4)
        assertThat(records.map { it.key }).containsOnly("order:42")
    }

    @Test
    fun `disabled feature preserves legacy key shapes and lengths`() {
        val service = service()
        val keys = listOf("~ns.pg1~billing:order-42", "ä".repeat(300))

        keys.forEach { key -> service.schedule("event", key) }

        keys.forEach { key -> assertThat(records.count { it.key == key }).isEqualTo(4) }
    }

    @Test
    fun `schedules one record per handler with shared and independent group keys`() {
        service(OutboxProcessingGroupSettings(enabled = true)).schedule("event", "order:42")

        assertThat(records).hasSize(4)
        assertThat(records.associate { it.handlerId to it.key })
            .containsEntry("invoice", "~ns.pg1~billing:order:42")
            .containsEntry("ledger", "~ns.pg1~billing:order:42")
            .containsEntry("notify", "~ns.pg1~notifications:order:42")
            .containsEntry("generic", "order:42")
        assertThat(records.map { it.handlerId }).doesNotContain("old-invoice")
        assertThat(records.map { it.id }).doesNotHaveDuplicates()
        assertThat(records.map { it.createdAt }).containsOnly(clock.instant())
        assertThat(records.map { it.nextRetryAt }).containsOnly(clock.instant())
        records.forEach { record ->
            assertThat(record.partition).isEqualTo(PartitionHasher.getPartitionForRecordKey(record.key))
        }
    }

    @Test
    fun `uses the group declared by a generic interface handler`() {
        service(OutboxProcessingGroupSettings(enabled = true), listOf(GenericHandler("audit")))
            .schedule("event", "order-42")

        assertThat(records).singleElement().satisfies({ record ->
            assertThat(record.handlerId).isEqualTo("generic")
            assertThat(record.key).isEqualTo("~ns.pg1~audit:order-42")
        })
        assertThat(selectionMetadata.map { it.processingGroup }).containsExactly("audit")
        assertThat(selectionMetadata.map { it.key }).containsExactly("order-42")
    }

    @Test
    fun `supports receives the default group when group scheduling is disabled`() {
        service(handlers = listOf(GenericHandler("audit"))).schedule("event", "order-42")

        assertThat(selectionMetadata.map { it.processingGroup }).containsExactly("default")
        assertThat(records.map { it.key }).containsExactly("order-42")
    }

    @Test
    fun `validates all encoded keys before saving any part of the fan-out`() {
        val service =
            service(
                OutboxProcessingGroupSettings(enabled = true),
                listOf(TypedHandler("default"), GenericHandler("audit")),
            )

        // The typed handler could accept this key; the generic handler selected last cannot.
        assertThatIllegalArgumentException()
            .isThrownBy { service.schedule("event", "a".repeat(250)) }
            .withMessageContaining("255-byte")
        assertThat(records).isEmpty()
    }

    @Test
    fun `validates keys only for handlers that support the event`() {
        genericSupports = false
        val service =
            service(
                OutboxProcessingGroupSettings(enabled = true),
                listOf(TypedHandler("default"), GenericHandler("audit")),
            )

        service.schedule("event", "a".repeat(250))

        assertThat(records).singleElement().satisfies({ record ->
            assertThat(record.handlerId).isEqualTo("ledger")
            assertThat(record.key).isEqualTo("a".repeat(250))
        })
    }

    @Test
    fun `rejects reserved prefixes when enabled before saving records`() {
        val service = service(OutboxProcessingGroupSettings(enabled = true))

        assertThatIllegalArgumentException()
            .isThrownBy { service.schedule("event", "~ns.pg1~billing:order-42") }
            .withMessageContaining("reserved")
        assertThat(records).isEmpty()
    }

    @Test
    fun `supports and instrumentation receive the business key and context stays unchanged`() {
        service(OutboxProcessingGroupSettings(enabled = true))
            .schedule("event", "order:42", mapOf("source" to "caller", "traceId" to "trace-1"))

        assertThat(selectionMetadata).singleElement().satisfies({ metadata ->
            assertThat(metadata.key).isEqualTo("order:42")
            assertThat(metadata.createdAt).isEqualTo(clock.instant())
            assertThat(metadata.context).containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    "source" to "caller",
                    "traceId" to "trace-1",
                ),
            )
        })
        assertThat(scheduleInvocations.map { it.recordKey }).containsExactly("order:42")
        records.forEach { record ->
            assertThat(record.context).containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    "source" to "caller",
                    "traceId" to "trace-1",
                ),
            )
        }
    }

    @Test
    fun `auto-generated keys remain shared across all deliveries of an event`() {
        val service = service(OutboxProcessingGroupSettings(enabled = true))

        service.schedule("event", mapOf("traceId" to "trace-1"))
        service.schedule("event")

        assertThat(records).hasSize(8)
        val eventKeys =
            records.chunked(4).map { batch ->
                val keys = batch.map { OutboxRecordKey.decode(it.key).businessKey }.toSet()
                assertThat(keys).hasSize(1)
                UUID.fromString(keys.single())
            }
        assertThat(eventKeys).doesNotHaveDuplicates()
        assertThat(records.take(4).map { it.context["traceId"] }).containsOnly("trace-1")
    }

    @Test
    fun `enabled scheduling still requires an active transaction`() {
        val service = service(OutboxProcessingGroupSettings(enabled = true))
        TransactionSynchronizationManager.clear()

        assertThatIllegalStateException()
            .isThrownBy { service.schedule("event", "order-42") }
            .withMessageContaining("active transaction")
        assertThat(records).isEmpty()
    }

    private fun service(
        settings: OutboxProcessingGroupSettings = OutboxProcessingGroupSettings(),
        handlers: List<Any> = listOf(AnnotatedHandlers(), TypedHandler(), GenericHandler()),
    ): OutboxService {
        val infrastructure =
            OutboxHandlerInfrastructureFactory(DefaultListableBeanFactory()) { emptyList() }
                .create(OutboxRetryPolicy.builder().build(), "default")
        handlers.forEachIndexed { index, handler -> infrastructure.register(handler, "handler-$index") }
        val repository = mockk<OutboxRecordRepository>()
        every { repository.save(capture(records)) } answers { records.last() }
        val contextProvider =
            object : OutboxContextProvider {
                override fun provide() = mapOf("source" to "provider")
            }
        val instrumentation =
            object : OutboxInstrumentation {
                override fun schedule(
                    invocation: OutboxScheduleInvocation,
                    action: () -> Unit,
                ) {
                    scheduleInvocations += invocation
                    action()
                }
            }
        return OutboxService(
            contextCollector = OutboxContextCollector(listOf(contextProvider)),
            handlerRegistry = infrastructure.handlerRegistry,
            outboxRecordRepository = repository,
            clock = clock,
            instrumentationSupplier = { instrumentation },
            processingGroups = settings,
        )
    }

    private class AnnotatedHandlers {
        @OutboxHandlerAnnotation(id = "invoice", aliases = ["old-invoice"], processingGroup = "billing")
        fun invoice(payload: String) = Unit

        @OutboxHandlerAnnotation(id = "notify", processingGroup = "notifications")
        fun notify(payload: String) = Unit
    }

    private class TypedHandler(
        private val group: String = "billing",
    ) : OutboxTypedHandler<String> {
        override fun getTypedHandlerIdentity() = OutboxHandlerIdentity("ledger")

        override fun getTypedHandlerProcessingGroup() = group

        override fun handle(
            payload: String,
            metadata: OutboxRecordMetadata,
        ) = Unit
    }

    private inner class GenericHandler(
        private val group: String = "default",
    ) : OutboxHandler {
        override fun getGenericHandlerIdentity() = OutboxHandlerIdentity("generic")

        override fun getGenericHandlerProcessingGroup() = group

        override fun supports(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ): Boolean {
            selectionMetadata += metadata
            return genericSupports
        }

        override fun handle(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) = Unit
    }
}
