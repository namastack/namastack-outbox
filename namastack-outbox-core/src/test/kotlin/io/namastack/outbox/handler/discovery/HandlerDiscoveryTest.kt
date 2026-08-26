package io.namastack.outbox.handler.discovery

import io.namastack.outbox.annotation.OutboxFallbackHandler
import io.namastack.outbox.handler.OutboxFailureContext
import io.namastack.outbox.handler.OutboxHandler
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.OutboxTypedHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import io.namastack.outbox.annotation.OutboxHandler as HandlerAnnotation

class HandlerDiscoveryTest {
    private val metadata =
        OutboxRecordMetadata(
            key = "record-key",
            handlerId = "handler-id",
            createdAt = Instant.parse("2025-01-01T00:00:00Z"),
            context = emptyMap(),
            failureCount = 0,
        )

    @Test
    fun `discovers annotated handler identity and matching fallback declaration`() {
        val bean = AnnotatedHandler()

        val declarations = HandlerDiscovery.discover(bean, "annotatedBean")

        assertThat(declarations.handlers).hasSize(1)

        with(declarations.handlers.single()) {
            assertThat(beanName).isEqualTo("annotatedBean")
            assertThat(this.bean).isSameAs(bean)
            assertThat(method.name).isEqualTo("handle")
            assertThat(source).isEqualTo(HandlerSource.ANNOTATION)
            assertThat(configuredId).isEqualTo("orders-v2")
            assertThat(configuredAliases).containsExactly("orders-v1")
            assertThat(lambdaBeanNameId).isNull()
            assertThat(supportsPayload("payload", metadata)).isTrue()
        }

        val fallback = declarations.fallbacks.single()

        assertThat(fallback.bean).isSameAs(bean)
        assertThat(fallback.method.name).isEqualTo("handleFailure")
        assertThat(fallback.source).isEqualTo(HandlerSource.ANNOTATION)
    }

    @Test
    fun `discovers generic interface identity and preserves supports behavior`() {
        val bean = SelectiveInterfaceHandler()

        val candidate = HandlerDiscovery.discover(bean, "interfaceBean").handlers.single()

        assertThat(candidate.source).isEqualTo(HandlerSource.GENERIC_INTERFACE)
        assertThat(candidate.configuredId).isEqualTo("generic-v2")
        assertThat(candidate.configuredAliases).containsExactly("generic-v1")
        assertThat(candidate.supportsPayload("accepted", metadata)).isTrue()
        assertThat(candidate.supportsPayload("rejected", metadata)).isFalse()
    }

    @Test
    fun `interface discovery ignores beans without a handler interface`() {
        assertThat(InterfaceHandlerDiscoverer.discover(PlainBean(), "plainBean")).isEmpty()
    }

    @Test
    fun `interface discovery returns one typed candidate for a typed handler`() {
        val candidates = InterfaceHandlerDiscoverer.discover(TypedInterfaceHandler(), "typedBean")

        assertThat(candidates).singleElement().satisfies({ candidate ->
            assertThat(candidate.source).isEqualTo(HandlerSource.TYPED_INTERFACE)
            assertThat(candidate.supportsPayload("payload", metadata)).isTrue()
        })
    }

    @Test
    fun `interface discovery returns typed and generic candidates when both interfaces are implemented`() {
        val candidates = InterfaceHandlerDiscoverer.discover(CombinedInterfaceHandler(), "combinedBean")

        assertThat(candidates.map { it.source })
            .containsExactly(HandlerSource.TYPED_INTERFACE, HandlerSource.GENERIC_INTERFACE)
    }

    private class AnnotatedHandler {
        @HandlerAnnotation(id = "orders-v2", aliases = ["orders-v1"])
        fun handle(payload: String) = Unit

        @OutboxFallbackHandler
        fun handleFailure(
            payload: String,
            context: OutboxFailureContext,
        ) = Unit
    }

    private class SelectiveInterfaceHandler : OutboxHandler {
        override fun getHandlerId() = "generic-v2"

        override fun getHandlerAliases() = setOf("generic-v1")

        override fun supports(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) = payload == "accepted" && metadata.key == "record-key"

        override fun handle(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) = Unit
    }

    private class PlainBean

    private class TypedInterfaceHandler : OutboxTypedHandler<String> {
        override fun handle(
            payload: String,
            metadata: OutboxRecordMetadata,
        ) = Unit
    }

    private class CombinedInterfaceHandler :
        OutboxTypedHandler<Any>,
        OutboxHandler {
        override fun getHandlerId(): String? = null

        override fun getHandlerAliases(): Set<String> = emptySet()

        override fun handle(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) = Unit
    }
}
