package io.namastack.outbox

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.annotation.OutboxEvent
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationContext
import org.springframework.context.PayloadApplicationEvent
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.context.event.SimpleApplicationEventMulticaster
import org.springframework.transaction.support.TransactionSynchronizationManager

open class OutboxEventMulticasterTest {
    private var outbox = mockk<Outbox>(relaxed = true)
    private var outboxProperties = OutboxProperties(processing = OutboxProperties.Processing(publishAfterSave = true))
    private var delegateEventMulticaster = mockk<SimpleApplicationEventMulticaster>()

    private lateinit var eventMulticaster: OutboxEventMulticaster

    @BeforeEach
    fun setUp() {
        TransactionSynchronizationManager.setActualTransactionActive(true)

        eventMulticaster =
            OutboxEventMulticaster(
                outbox = outbox,
                outboxProperties = outboxProperties,
                delegateEventMulticaster = delegateEventMulticaster,
            )

        every { delegateEventMulticaster.multicastEvent(any(), any()) } answers { }
    }

    @AfterEach
    fun tearDown() {
        TransactionSynchronizationManager.clear()
    }

    @Test
    fun `passes non annotated events to parent`() {
        val event = PayloadApplicationEvent(this, NotAnnotatedTestEvent(id = "agg-1"))

        eventMulticaster.multicastEvent(event)

        verify(exactly = 1) {
            delegateEventMulticaster.multicastEvent(event, any())
        }
    }

    @Test
    fun `passes non PayloadApplicationEvent to parent`() {
        val event = ContextRefreshedEvent(mockk<ApplicationContext>())

        eventMulticaster.multicastEvent(event)

        verify(exactly = 1) {
            delegateEventMulticaster.multicastEvent(event, any())
        }
    }

    @Test
    open fun `stores annotated events in outbox when transaction is active`() {
        TransactionSynchronizationManager.setActualTransactionActive(true)

        val payload = AnnotatedTestEvent(id = "agg-1")
        val event = PayloadApplicationEvent(this, payload)

        eventMulticaster.multicastEvent(event)

        verify(exactly = 1) { outbox.schedule(payload, "agg-1") }

        verify(exactly = 1) {
            delegateEventMulticaster.multicastEvent(any<PayloadApplicationEvent<*>>(), any())
        }
    }

    @Test
    fun `does not store annotated events in outbox when no transaction is active`() {
        TransactionSynchronizationManager.setActualTransactionActive(false)

        val event = PayloadApplicationEvent(this, AnnotatedTestEvent(id = "agg-1"))

        assertThatThrownBy {
            eventMulticaster.multicastEvent(event)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("OutboxEvent requires an active transaction")
    }

    @Test
    fun `does not publish to parent when publishAfterSave is false`() {
        val localProperties = OutboxProperties(processing = OutboxProperties.Processing(publishAfterSave = false))
        val localMulticaster =
            OutboxEventMulticaster(
                outbox = outbox,
                outboxProperties = localProperties,
                delegateEventMulticaster = delegateEventMulticaster,
            )

        val event = PayloadApplicationEvent(this, AnnotatedTestEvent(id = "agg-1"))

        localMulticaster.multicastEvent(event)

        verify(exactly = 0) {
            delegateEventMulticaster.multicastEvent(any(), any())
        }
    }

    @Test
    fun `resolves spel expression for record key`() {
        val event = PayloadApplicationEvent(this, SpELAnnotatedTestEvent(customId = "agg-1"))

        eventMulticaster.multicastEvent(event)

        verify(exactly = 1) { outbox.schedule(any(), "agg-1") }
    }

    @Test
    fun `throws exception when spel expression returns non string value`() {
        val event = PayloadApplicationEvent(this, NonStringIdEvent(id = 42))

        assertThatThrownBy {
            eventMulticaster.multicastEvent(event)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Failed to resolve key from SpEL: 'id'. Valid examples: 'id', '#this.id', '#root.id'")
    }

    @Test
    fun `generates UUID key when key annotation is empty`() {
        val event = PayloadApplicationEvent(this, EmptyKeyAnnotatedEvent(id = "agg-1"))

        eventMulticaster.multicastEvent(event)

        verify(exactly = 1) { outbox.schedule(any(), any()) }
    }

    @Test
    fun `generates unique UUIDs for different events with empty key`() {
        val event1 = PayloadApplicationEvent(this, EmptyKeyAnnotatedEvent(id = "agg-1"))
        val event2 = PayloadApplicationEvent(this, EmptyKeyAnnotatedEvent(id = "agg-2"))
        val keys = mutableListOf<String>()

        every { outbox.schedule(any(), any()) } answers {
            keys.add(secondArg())
        }

        eventMulticaster.multicastEvent(event1)
        eventMulticaster.multicastEvent(event2)

        verify(exactly = 2) { outbox.schedule(any(), any()) }
        assert(keys.size == 2 && keys[0] != keys[1]) {
            "Expected unique UUIDs but got: ${keys[0]} and ${keys[1]}"
        }
    }

    @Test
    fun `throws exception when SpEL expression returns null`() {
        val event = PayloadApplicationEvent(this, NullableKeyEvent(id = "agg-1", optionalKey = null))

        assertThatThrownBy {
            eventMulticaster.multicastEvent(event)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Failed to resolve key from SpEL")
    }

    @OutboxEvent(key = "#this.customId")
    data class SpELAnnotatedTestEvent(
        val customId: String,
    )

    @OutboxEvent(key = "id")
    data class AnnotatedTestEvent(
        val id: String,
    )

    @OutboxEvent(key = "id")
    data class NonStringIdEvent(
        val id: Int,
    )

    data class NotAnnotatedTestEvent(
        val id: String,
    )

    @OutboxEvent(key = "")
    data class EmptyKeyAnnotatedEvent(
        val id: String,
    )

    @OutboxEvent(key = "#this.optionalKey")
    data class NullableKeyEvent(
        val id: String,
        val optionalKey: String?,
    )
}
