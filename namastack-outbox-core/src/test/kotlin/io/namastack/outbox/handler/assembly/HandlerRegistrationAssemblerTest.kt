package io.namastack.outbox.handler.assembly

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.annotation.OutboxFallbackHandler
import io.namastack.outbox.annotation.OutboxRetryable
import io.namastack.outbox.handler.OutboxFailureContext
import io.namastack.outbox.handler.OutboxHandler
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.OutboxTypedHandler
import io.namastack.outbox.handler.OutboxTypedHandlerWithFallback
import io.namastack.outbox.handler.discovery.HandlerDiscovery
import io.namastack.outbox.handler.method.handler.GenericHandlerMethod
import io.namastack.outbox.handler.method.handler.TypedHandlerMethod
import io.namastack.outbox.retry.OutboxRetryAware
import io.namastack.outbox.retry.OutboxRetryPolicy
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import io.namastack.outbox.annotation.OutboxHandler as OutboxHandlerAnnotation

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
    fun `assembles typed Any interface handler as typed`() {
        val registration = assembler.assemble(HandlerDiscovery.discover(TypedAnyHandler(), "typedAnyBean")).single()

        val primary = registration.primary as TypedHandlerMethod
        assertThat(primary.paramType).isEqualTo(Any::class)
    }

    @Test
    fun `rejects ambiguous generic and typed Any interface handler with dedicated message`() {
        assertThatThrownBy {
            assembler.assemble(HandlerDiscovery.discover(AmbiguousInterfaceHandler(), "ambiguousBean"))
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("implements both OutboxHandler and OutboxTypedHandler<Any>")
    }

    @Test
    fun `assembles distinct typed interface and annotated overloads with their matching fallbacks`() {
        val registrations =
            assembler.assemble(
                HandlerDiscovery.discover(OverloadedTypedHandlerWithFallback(), "overloadedTypedBean"),
            )

        assertThat(registrations).hasSize(2)
        val byPayloadType = registrations.associateBy { (it.primary as TypedHandlerMethod).paramType }
        val interfaceFallback = byPayloadType.getValue(InterfacePayload::class).fallback
        val annotatedFallback = byPayloadType.getValue(AnnotatedPayload::class).fallback
        assertThat(interfaceFallback?.method?.parameterTypes?.first()).isEqualTo(InterfacePayload::class.java)
        assertThat(annotatedFallback?.method?.parameterTypes?.first()).isEqualTo(AnnotatedPayload::class.java)
    }

    @Test
    fun `resolves inherited generic interface payload for handler and fallback`() {
        val registration =
            assembler.assemble(HandlerDiscovery.discover(InheritedGenericHandler(), "inheritedGenericBean")).single()

        assertThat((registration.primary as TypedHandlerMethod).paramType).isEqualTo(InterfacePayload::class)
        assertThat(registration.fallback).isNotNull()
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

    @Test
    fun `uses OutboxRetryAware policy for annotated handler`() {
        val retryPolicy = mockk<OutboxRetryPolicy>()
        val bean = RetryAwareAnnotatedHandler(retryPolicy)

        val registration = assembler.assemble(HandlerDiscovery.discover(bean, "retryAwareAnnotated")).single()

        assertThat(registration.explicitRetryPolicy).isSameAs(retryPolicy)
        assertThat(bean.retryPolicyRequestCount).isEqualTo(1)
    }

    @Test
    fun `method retry annotation takes precedence over OutboxRetryAware policy`() {
        val annotationPolicy = mockk<OutboxRetryPolicy>()
        val beanPolicy = mockk<OutboxRetryPolicy>()
        every { retryPolicies.getRetryPolicy("methodPolicy") } returns annotationPolicy
        val bean = RetryAwareAnnotatedHandlerWithMethodPolicy(beanPolicy)

        val registration = assembler.assemble(HandlerDiscovery.discover(bean, "annotatedOverride")).single()

        assertThat(registration.explicitRetryPolicy).isSameAs(annotationPolicy)
        assertThat(bean.retryPolicyRequestCount).isZero()
        verify(exactly = 1) { retryPolicies.getRetryPolicy("methodPolicy") }
    }

    private class CompleteAnnotatedHandler {
        @OutboxHandlerAnnotation(id = "orders-v2", aliases = ["orders-v1"])
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

    private class TypedAnyHandler : OutboxTypedHandler<Any> {
        override fun handle(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) = Unit
    }

    private class AmbiguousInterfaceHandler :
        OutboxTypedHandler<Any>,
        OutboxHandler {
        override fun handle(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) = Unit
    }

    private class OverloadedTypedHandlerWithFallback : OutboxTypedHandlerWithFallback<InterfacePayload> {
        override fun handle(
            payload: InterfacePayload,
            metadata: OutboxRecordMetadata,
        ) = Unit

        @OutboxHandlerAnnotation
        fun handle(
            payload: AnnotatedPayload,
            metadata: OutboxRecordMetadata,
        ) = Unit

        override fun handleFailure(
            payload: InterfacePayload,
            context: OutboxFailureContext,
        ) = Unit

        @OutboxFallbackHandler
        fun handleFailure(
            payload: AnnotatedPayload,
            context: OutboxFailureContext,
        ) = Unit
    }

    private abstract class GenericHandlerWithFallback<T> : OutboxTypedHandlerWithFallback<T> {
        override fun handle(
            payload: T,
            metadata: OutboxRecordMetadata,
        ) = Unit

        override fun handleFailure(
            payload: T,
            context: OutboxFailureContext,
        ) = Unit
    }

    private class InheritedGenericHandler : GenericHandlerWithFallback<InterfacePayload>()

    private class InterfacePayload

    private class AnnotatedPayload

    private class UnsupportedHandler {
        @OutboxHandlerAnnotation
        fun handle() = Unit
    }

    private class DefaultRetryHandler {
        @OutboxHandlerAnnotation
        @OutboxRetryable
        fun handle(payload: String) = Unit
    }

    private class RetryAwareAnnotatedHandler(
        private val retryPolicy: OutboxRetryPolicy,
    ) : OutboxRetryAware {
        var retryPolicyRequestCount = 0
            private set

        @OutboxHandlerAnnotation
        fun handle(payload: String) = Unit

        override fun getRetryPolicy(): OutboxRetryPolicy {
            retryPolicyRequestCount++
            return retryPolicy
        }
    }

    private class RetryAwareAnnotatedHandlerWithMethodPolicy(
        private val retryPolicy: OutboxRetryPolicy,
    ) : OutboxRetryAware {
        var retryPolicyRequestCount = 0
            private set

        @OutboxHandlerAnnotation
        @OutboxRetryable(name = "methodPolicy")
        fun handle(payload: String) = Unit

        override fun getRetryPolicy(): OutboxRetryPolicy {
            retryPolicyRequestCount++
            return retryPolicy
        }
    }
}
