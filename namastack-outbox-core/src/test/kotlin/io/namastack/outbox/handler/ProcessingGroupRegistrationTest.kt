package io.namastack.outbox.handler

import io.namastack.outbox.retry.OutboxRetryPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.core.annotation.AliasFor
import io.namastack.outbox.annotation.OutboxHandler as OutboxHandlerAnnotation

class ProcessingGroupRegistrationTest {
    private val infrastructure =
        OutboxHandlerInfrastructureFactory(DefaultListableBeanFactory()) { emptyList() }
            .create(OutboxRetryPolicy.builder().build(), "default")
    private val registry = infrastructure.handlerRegistry

    @Test
    fun `retains all matching handlers even when they share a processing group`() {
        infrastructure.register(AnnotatedHandlers(), "annotated")

        assertThat(registry.getHandlersForPayloadType(String::class).map { it.id })
            .containsExactlyInAnyOrder("invoice", "ledger", "notification")
        assertThat(registry.getRegistrationById("invoice")?.processingGroup).isEqualTo("billing")
        assertThat(registry.getRegistrationById("ledger")?.processingGroup).isEqualTo("billing")
        assertThat(registry.getRegistrationById("notification")?.processingGroup).isEqualTo("notifications")
        assertThat(registry.getRegistrationById("old-invoice"))
            .isSameAs(registry.getRegistrationById("invoice"))
    }

    @Test
    fun `retains group declared through a composed annotation`() {
        infrastructure.register(ComposedHandler(), "composed")

        assertThat(registry.getRegistrationById("composed")?.processingGroup).isEqualTo("billing")
    }

    @Test
    fun `keeps interface and annotation groups separate on the same bean`() {
        infrastructure.register(MixedHandler(), "mixed")

        assertThat(registry.getRegistrationById("typed")?.processingGroup).isEqualTo("billing")
        assertThat(registry.getRegistrationById("annotated")?.processingGroup).isEqualTo("audit")
    }

    @Test
    fun `retains generic interface group`() {
        infrastructure.register(GenericHandler("audit"), "generic")

        assertThat(registry.getRegistrationById("generic")?.processingGroup).isEqualTo("audit")
    }

    @Test
    fun `Java lambda retains default group and bean name identity`() {
        infrastructure.register(LambdaOutboxHandlerFactory.create(), "lambda")

        assertThat(registry.getRegistrationById("lambda")?.processingGroup).isEqualTo(OutboxProcessingGroup.DEFAULT)
    }

    @Test
    fun `rejects invalid annotation group before registering any handler from the bean`() {
        assertThatIllegalArgumentException()
            .isThrownBy { infrastructure.register(InvalidAnnotatedHandlers(), "invalid") }
            .withMessageContaining("Processing group")

        assertThat(registry.findAllHandlerDescriptors()).isEmpty()
    }

    @Test
    fun `rejects invalid interface group before registration`() {
        assertThatIllegalArgumentException()
            .isThrownBy { infrastructure.register(GenericHandler("Audit"), "invalid") }
            .withMessageContaining("Processing group")

        assertThat(registry.findAllHandlerDescriptors()).isEmpty()
    }

    private class AnnotatedHandlers {
        @OutboxHandlerAnnotation(id = "invoice", aliases = ["old-invoice"], processingGroup = "billing")
        fun invoice(payload: String) = Unit

        @OutboxHandlerAnnotation(id = "ledger", processingGroup = "billing")
        fun ledger(payload: String) = Unit

        @OutboxHandlerAnnotation(id = "notification", processingGroup = "notifications")
        fun notify(payload: String) = Unit
    }

    @Target(AnnotationTarget.FUNCTION)
    @Retention(AnnotationRetention.RUNTIME)
    @OutboxHandlerAnnotation(id = "composed")
    private annotation class ComposedOutboxHandler(
        @get:AliasFor(annotation = OutboxHandlerAnnotation::class, attribute = "processingGroup")
        val processingGroup: String = OutboxProcessingGroup.DEFAULT,
    )

    private class ComposedHandler {
        @ComposedOutboxHandler(processingGroup = "billing")
        fun handle(payload: String) = Unit
    }

    private class MixedHandler : OutboxTypedHandler<String> {
        override fun getTypedHandlerIdentity() = OutboxHandlerIdentity("typed")

        override fun getTypedHandlerProcessingGroup() = "billing"

        override fun handle(
            payload: String,
            metadata: OutboxRecordMetadata,
        ) = Unit

        @OutboxHandlerAnnotation(id = "annotated", processingGroup = "audit")
        fun audit(payload: String) = Unit
    }

    private class GenericHandler(
        private val group: String,
    ) : OutboxHandler {
        override fun getGenericHandlerIdentity() = OutboxHandlerIdentity("generic")

        override fun getGenericHandlerProcessingGroup() = group

        override fun handle(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) = Unit
    }

    private class InvalidAnnotatedHandlers {
        @OutboxHandlerAnnotation(id = "valid", processingGroup = "billing")
        fun valid(payload: String) = Unit

        @OutboxHandlerAnnotation(id = "invalid", processingGroup = "")
        fun invalid(payload: String) = Unit
    }
}
