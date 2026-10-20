package io.namastack.outbox

import io.mockk.Called
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.context.OutboxContextCollector
import io.namastack.outbox.handler.registry.OutboxHandlerRegistry
import io.namastack.outbox.instrumentation.OutboxInstrumentation
import io.namastack.outbox.instrumentation.OutboxScheduleInvocation
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock

class OutboxServiceTransactionTest {
    private val contextCollector = mockk<OutboxContextCollector>(relaxed = true)
    private val handlerRegistry = mockk<OutboxHandlerRegistry>(relaxed = true)
    private val recordRepository = mockk<OutboxRecordRepository>(relaxed = true)

    @AfterEach
    fun clearTransactionState() {
        TransactionSynchronizationManager.clear()
    }

    @ParameterizedTest
    @EnumSource(ScheduleOverload::class)
    fun `rejects scheduling without a transaction before any scheduling work`(overload: ScheduleOverload) {
        assertRejected(overload)
    }

    @ParameterizedTest
    @EnumSource(ScheduleOverload::class)
    fun `synchronization alone does not satisfy the transaction requirement`(overload: ScheduleOverload) {
        TransactionSynchronizationManager.initSynchronization()

        assertRejected(overload)
    }

    @Test
    fun `instrumentation observes and propagates the transaction failure`() {
        val observedFailures = mutableListOf<Throwable>()
        val instrumentation =
            object : OutboxInstrumentation {
                override fun schedule(
                    invocation: OutboxScheduleInvocation,
                    action: () -> Unit,
                ) {
                    try {
                        action()
                    } catch (failure: Throwable) {
                        observedFailures += failure
                        throw failure
                    }
                }
            }

        val failure = catchThrowable { service(instrumentation).schedule("payload") }

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(observedFailures).containsExactly(failure)

        verify { listOf(contextCollector, handlerRegistry, recordRepository) wasNot Called }
    }

    private fun assertRejected(overload: ScheduleOverload) {
        val outbox = service()

        assertThatThrownBy { overload.schedule(outbox) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage(
                "Outbox.schedule requires an active transaction. " +
                    "Call it within a Spring-managed @Transactional method or TransactionTemplate.",
            )

        verify { listOf(contextCollector, handlerRegistry, recordRepository) wasNot Called }
    }

    private fun service(instrumentation: OutboxInstrumentation = OutboxInstrumentation.NOOP) =
        OutboxService(
            contextCollector = contextCollector,
            handlerRegistry = handlerRegistry,
            outboxRecordRepository = recordRepository,
            clock = Clock.systemUTC(),
            instrumentationSupplier = { instrumentation },
        )

    enum class ScheduleOverload {
        PAYLOAD,
        KEY,
        CONTEXT,
        KEY_AND_CONTEXT,
        ;

        fun schedule(outbox: Outbox) {
            when (this) {
                PAYLOAD -> outbox.schedule("payload")
                KEY -> outbox.schedule("payload", "key")
                CONTEXT -> outbox.schedule("payload", mapOf("tenant" to "north"))
                KEY_AND_CONTEXT -> outbox.schedule("payload", "key", mapOf("tenant" to "north"))
            }
        }
    }
}
