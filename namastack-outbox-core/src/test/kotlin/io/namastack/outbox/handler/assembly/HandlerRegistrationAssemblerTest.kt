package io.namastack.outbox.handler.assembly

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.annotation.OutboxFallbackHandler
import io.namastack.outbox.annotation.OutboxRetryable
import io.namastack.outbox.handler.OutboxFailureContext
import io.namastack.outbox.handler.OutboxHandler
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.discovery.HandlerDiscovery
import io.namastack.outbox.handler.method.handler.GenericHandlerMethod
import io.namastack.outbox.handler.method.handler.TypedHandlerMethod
import io.namastack.outbox.retry.OutboxRetryPolicy
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import io.namastack.outbox.annotation.OutboxHandler as HandlerAnnotation

class HandlerRegistrationAssemblerTest {
    private val retryPolicies = mockk<OutboxRetryPolicyRegistry>()
    private val assembler = HandlerRegistrationAssembler(retryPolicies)
    private val metadata =
        OutboxRecordMetadata(
            key = "record-key",
            handlerId = "handler-id",
            createdAt = Instant.parse("2025-01-01T00:00:00Z"),
            context = emptyMap(),
            failureCount = 0,
        )

    @Test
    fun `assembles typed handler with identity fallback and named retry policy`() {
        val retryPolicy = mockk<OutboxRetryPolicy>()
        every { retryPolicies.getRetryPolicy("namedPolicy") } returns retryPolicy
        val bean = CompleteAnnotatedHandler()

        val registration = assembler.assemble(HandlerDiscovery.discover(bean, "ordersBean")).single()

        assertThat(registration.beanName).isEqualTo("ordersBean")
        assertThat(registration.primary).isInstanceOf(TypedHandlerMethod::class.java)
        assertThat(registration.primary.id).isEqualTo("orders-v2")
        assertThat(registration.primary.aliases).contains("orders-v1")
        assertThat(registration.fallback).isNotNull
        assertThat(registration.explicitRetryPolicy).isSameAs(retryPolicy)
        verify { retryPolicies.getRetryPolicy("namedPolicy") }
    }

    @Test
    fun `assembles generic interface handler with scheduling support and lazy default retry`() {
        val bean = SelectiveGenericHandler()

        val registration = assembler.assemble(HandlerDiscovery.discover(bean, "genericBean")).single()

        val primary = registration.primary as GenericHandlerMethod
        assertThat(primary.supportsPayload("accepted", metadata)).isTrue()
        assertThat(primary.supportsPayload("rejected", metadata)).isFalse()
        assertThat(registration.fallback).isNull()
        assertThat(registration.explicitRetryPolicy).isNull()
    }

    @Test
    fun `skips unsupported annotated signature`() {
        val registrations = assembler.assemble(HandlerDiscovery.discover(UnsupportedHandler(), "unsupportedBean"))

        assertThat(registrations).isEmpty()
    }

    @Test
    fun `default retry annotation leaves policy resolution lazy`() {
        val registration = assembler.assemble(HandlerDiscovery.discover(DefaultRetryHandler(), "defaultRetry")).single()

        assertThat(registration.explicitRetryPolicy).isNull()
    }

    private class CompleteAnnotatedHandler {
        @HandlerAnnotation(id = "orders-v2", aliases = ["orders-v1"])
        @OutboxRetryable(name = "namedPolicy")
        fun handle(payload: String) = Unit

        @OutboxFallbackHandler
        fun handleFailure(
            payload: String,
            context: OutboxFailureContext,
        ) = Unit
    }

    private class SelectiveGenericHandler : OutboxHandler {
        override fun supports(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) = payload == "accepted" && metadata.key == "record-key"

        override fun handle(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) = Unit
    }

    private class UnsupportedHandler {
        @HandlerAnnotation
        fun handle() = Unit
    }

    private class DefaultRetryHandler {
        @HandlerAnnotation
        @OutboxRetryable
        fun handle(payload: String) = Unit
    }
}
