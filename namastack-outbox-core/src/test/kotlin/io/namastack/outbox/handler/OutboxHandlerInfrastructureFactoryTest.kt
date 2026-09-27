package io.namastack.outbox.handler

import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.annotation.OutboxFallbackHandler
import io.namastack.outbox.annotation.OutboxRetryable
import io.namastack.outbox.instrumentation.OutboxHandlerInvocation
import io.namastack.outbox.instrumentation.OutboxInstrumentation
import io.namastack.outbox.retry.OutboxRetryPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import io.namastack.outbox.annotation.OutboxHandler as OutboxHandlerAnnotation

class OutboxHandlerInfrastructureFactoryTest {
    private val beanFactory = DefaultListableBeanFactory()
    private val defaultRetryPolicy = OutboxRetryPolicy.builder().build()

    @Test
    fun `registers only selected handlers with their fallback and retry policy`() {
        val selectedRetryPolicy = OutboxRetryPolicy.builder().maxRetries(7).build()
        beanFactory.registerSingleton("selectedRetryPolicy", selectedRetryPolicy)
        val infrastructure = factory().create(defaultRetryPolicy, "orders")

        infrastructure.register(FilteredHandler(), "filteredHandler") { it.name == "selected" }

        assertThat(infrastructure.handlerRegistry.findAllHandlerDescriptors().map { it.id })
            .containsExactly("selected")
        val registration = requireNotNull(infrastructure.handlerRegistry.getRegistrationById("selected"))
        assertThat(registration.fallback).isNotNull()
        assertThat(registration.explicitRetryPolicy).isSameAs(selectedRetryPolicy)
        assertThat(infrastructure.handlerRegistry.getRegistrationById("ignored")).isNull()
    }

    @Test
    fun `keeps handler registrations and default retry policies isolated`() {
        val firstPolicy = OutboxRetryPolicy.builder().maxRetries(2).build()
        val secondPolicy = OutboxRetryPolicy.builder().maxRetries(5).build()
        val first = factory().create(firstPolicy, "orders")
        val second = factory().create(secondPolicy, "payments")

        first.register(DuplicateHandler(), "firstHandler")
        second.register(DuplicateHandler(), "secondHandler")

        assertThat(first.handlerRegistry.getHandlerById("shared-handler")).isNotNull()
        assertThat(second.handlerRegistry.getHandlerById("shared-handler")).isNotNull()
        assertThat(first.retryPolicyRegistry.getByHandlerId("shared-handler")).isSameAs(firstPolicy)
        assertThat(second.retryPolicyRegistry.getByHandlerId("shared-handler")).isSameAs(secondPolicy)
    }

    @Test
    fun `composes instrumentation in order and supplies the channel name`() {
        val events = mutableListOf<String>()
        val channels = mutableListOf<String>()
        val infrastructure =
            factory(
                instrumentation("first", events, channels),
                instrumentation("second", events, channels),
            ).create(defaultRetryPolicy, "orders")
        infrastructure.register(ObservedHandler(events), "observedHandler")

        infrastructure.handlerInvoker.dispatch(record("observed-handler"))

        assertThat(events).containsExactly("first-before", "second-before", "handler", "second-after", "first-after")
        assertThat(channels).containsExactly("orders", "orders")
    }

    @Test
    fun `validates declarations before applying handler selection`() {
        val infrastructure = factory().create(defaultRetryPolicy, "orders")

        assertThatThrownBy {
            infrastructure.register(AmbiguousInterfaceHandler(), "ambiguousHandler") { false }
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("implements both OutboxHandler and OutboxTypedHandler<Any>")
    }

    private fun factory(vararg instrumentations: OutboxInstrumentation) =
        OutboxHandlerInfrastructureFactory(beanFactory) { instrumentations.toList() }

    private fun instrumentation(
        name: String,
        events: MutableList<String>,
        channels: MutableList<String>,
    ) = object : OutboxInstrumentation {
        override fun invokeHandler(
            invocation: OutboxHandlerInvocation,
            action: () -> Unit,
        ) {
            events += "$name-before"
            channels += invocation.channel
            try {
                action()
            } finally {
                events += "$name-after"
            }
        }
    }

    private fun record(handlerId: String): OutboxRecord<String> {
        val builder = OutboxRecord.Builder<String>()
        return builder
            .key("record-key")
            .payload("payload")
            .handlerId(handlerId)
            .build(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))
    }

    @Suppress("UNUSED_PARAMETER")
    private class FilteredHandler {
        @OutboxHandlerAnnotation(id = "selected")
        @OutboxRetryable(name = "selectedRetryPolicy")
        fun selected(payload: SelectedPayload) = Unit

        @OutboxFallbackHandler
        fun selectedFallback(
            payload: SelectedPayload,
            context: OutboxFailureContext,
        ) = Unit

        @OutboxHandlerAnnotation(id = "ignored")
        fun ignored(payload: IgnoredPayload) = Unit
    }

    @Suppress("UNUSED_PARAMETER")
    private class DuplicateHandler {
        @OutboxHandlerAnnotation(id = "shared-handler")
        fun handle(payload: String) = Unit
    }

    private class ObservedHandler(
        private val events: MutableList<String>,
    ) {
        @OutboxHandlerAnnotation(id = "observed-handler")
        fun handle(payload: String) {
            events += "handler"
        }
    }

    private class AmbiguousInterfaceHandler :
        OutboxTypedHandler<Any>,
        OutboxHandler {
        override fun handle(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) = Unit
    }

    private class SelectedPayload

    private class IgnoredPayload
}
