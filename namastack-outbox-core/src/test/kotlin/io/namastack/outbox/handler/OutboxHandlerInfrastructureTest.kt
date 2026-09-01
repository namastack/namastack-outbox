package io.namastack.outbox.handler

import io.namastack.outbox.annotation.OutboxFallbackHandler
import io.namastack.outbox.annotation.OutboxRetryable
import io.namastack.outbox.retry.OutboxRetryPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import io.namastack.outbox.annotation.OutboxHandler as OutboxHandlerAnnotation

class OutboxHandlerInfrastructureTest {
    private val selectedRetryPolicy = OutboxRetryPolicy.builder().build()

    @Test
    fun `registers only selected primary with its fallback and retry policy`() {
        val infrastructure = infrastructure()

        infrastructure.register(FilteredHandler(), "filteredHandler") { it.name == "selected" }

        assertThat(infrastructure.handlerRegistry.findAllHandlerDescriptors().map { it.id })
            .containsExactly("selected")
        val registration = requireNotNull(infrastructure.handlerRegistry.getRegistrationById("selected"))
        assertThat(registration.fallback).isNotNull()
        assertThat(registration.explicitRetryPolicy).isSameAs(selectedRetryPolicy)
        assertThat(infrastructure.handlerRegistry.getRegistrationById("ignored")).isNull()
    }

    @Test
    fun `validates unselected declarations before applying primary predicate`() {
        val infrastructure = infrastructure()

        assertThatThrownBy {
            infrastructure.register(AmbiguousInterfaceHandler(), "ambiguousHandler") { false }
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("implements both OutboxHandler and OutboxTypedHandler<Any>")
    }

    @Test
    fun `keeps duplicate handler ids isolated between infrastructures`() {
        val first = infrastructure()
        val second = infrastructure()

        first.register(DuplicateHandler(), "firstHandler")
        second.register(DuplicateHandler(), "secondHandler")

        assertThat(first.handlerRegistry.getHandlerById("shared-handler")).isNotNull()
        assertThat(second.handlerRegistry.getHandlerById("shared-handler")).isNotNull()
    }

    private fun infrastructure(): OutboxHandlerInfrastructure {
        val beanFactory = DefaultListableBeanFactory()
        beanFactory.registerSingleton("outboxRetryPolicy", OutboxRetryPolicy.builder().build())
        beanFactory.registerSingleton("selectedRetryPolicy", selectedRetryPolicy)
        return OutboxHandlerInfrastructure(beanFactory)
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

    private class AmbiguousInterfaceHandler :
        OutboxTypedHandler<Any>,
        OutboxHandler {
        override fun handle(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) = Unit
    }

    @Suppress("UNUSED_PARAMETER")
    private class DuplicateHandler {
        @OutboxHandlerAnnotation(id = "shared-handler")
        fun handle(payload: String) = Unit
    }

    private class SelectedPayload

    private class IgnoredPayload
}
