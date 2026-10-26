package io.namastack.outbox.handler

import io.namastack.outbox.retry.OutboxRetryPolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.core.annotation.AliasFor
import io.namastack.outbox.annotation.OutboxHandler as OutboxHandlerAnnotation

class ConsumerGroupRegistrationTest {
    private val infrastructure =
        OutboxHandlerInfrastructureFactory(DefaultListableBeanFactory()) { emptyList() }
            .create(OutboxRetryPolicy.builder().build(), "default")
    private val registry = infrastructure.handlerRegistry

    @Test
    fun `retains all matching handlers even when they share a consumer group`() {
        infrastructure.register(AnnotatedHandlers(), "annotated")

        assertThat(registry.getHandlersForPayloadType(String::class).map { it.id })
            .containsExactlyInAnyOrder("invoice", "ledger", "notification")
        assertThat(registry.getRegistrationById("invoice")?.consumerGroup).isEqualTo("billing")
        assertThat(registry.getRegistrationById("ledger")?.consumerGroup).isEqualTo("billing")
        assertThat(registry.getRegistrationById("notification")?.consumerGroup).isEqualTo("notifications")
        assertThat(registry.getRegistrationById("old-invoice"))
            .isSameAs(registry.getRegistrationById("invoice"))
    }

    @Test
    fun `retains group declared through a composed annotation`() {
        infrastructure.register(ComposedHandler(), "composed")

        assertThat(registry.getRegistrationById("composed")?.consumerGroup).isEqualTo("billing")
    }

    @Test
    fun `keeps interface and annotation groups separate on the same bean`() {
        infrastructure.register(MixedHandler(), "mixed")

        assertThat(registry.getRegistrationById("typed")?.consumerGroup).isEqualTo("billing")
        assertThat(registry.getRegistrationById("annotated")?.consumerGroup).isEqualTo("audit")
    }

    @Test
    fun `retains generic interface group`() {
        infrastructure.register(GenericHandler("audit"), "generic")

        assertThat(registry.getRegistrationById("generic")?.consumerGroup).isEqualTo("audit")
    }

    @Test
    fun `Java lambda retains default group and bean name identity`() {
        infrastructure.register(LambdaOutboxHandlerFactory.create(), "lambda")

        assertThat(registry.getRegistrationById("lambda")?.consumerGroup).isEqualTo(OutboxConsumerGroup.DEFAULT)
    }

    @Test
    fun `rejects invalid annotation group before registering any handler from the bean`() {
        assertThatIllegalArgumentException()
            .isThrownBy { infrastructure.register(InvalidAnnotatedHandlers(), "invalid") }
            .withMessageContaining("Consumer group")

        assertThat(registry.findAllHandlerDescriptors()).isEmpty()
    }

    @Test
    fun `rejects invalid interface group before registration`() {
        assertThatIllegalArgumentException()
            .isThrownBy { infrastructure.register(GenericHandler("Audit"), "invalid") }
            .withMessageContaining("Consumer group")

        assertThat(registry.findAllHandlerDescriptors()).isEmpty()
    }

    private class AnnotatedHandlers {
        @OutboxHandlerAnnotation(id = "invoice", aliases = ["old-invoice"], consumerGroup = "billing")
        fun invoice(payload: String) = Unit

        @OutboxHandlerAnnotation(id = "ledger", consumerGroup = "billing")
        fun ledger(payload: String) = Unit

        @OutboxHandlerAnnotation(id = "notification", consumerGroup = "notifications")
        fun notify(payload: String) = Unit
    }

    @Target(AnnotationTarget.FUNCTION)
    @Retention(AnnotationRetention.RUNTIME)
    @OutboxHandlerAnnotation(id = "composed")
    private annotation class ComposedOutboxHandler(
        @get:AliasFor(annotation = OutboxHandlerAnnotation::class, attribute = "consumerGroup")
        val consumerGroup: String = OutboxConsumerGroup.DEFAULT,
    )

    private class ComposedHandler {
        @ComposedOutboxHandler(consumerGroup = "billing")
        fun handle(payload: String) = Unit
    }

    private class MixedHandler : OutboxTypedHandler<String> {
        override fun getTypedHandlerIdentity() = OutboxHandlerIdentity("typed")

        override fun getTypedHandlerConsumerGroup() = "billing"

        override fun handle(
            payload: String,
            metadata: OutboxRecordMetadata,
        ) = Unit

        @OutboxHandlerAnnotation(id = "annotated", consumerGroup = "audit")
        fun audit(payload: String) = Unit
    }

    private class GenericHandler(
        private val group: String,
    ) : OutboxHandler {
        override fun getGenericHandlerIdentity() = OutboxHandlerIdentity("generic")

        override fun getGenericHandlerConsumerGroup() = group

        override fun handle(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) = Unit
    }

    private class InvalidAnnotatedHandlers {
        @OutboxHandlerAnnotation(id = "valid", consumerGroup = "billing")
        fun valid(payload: String) = Unit

        @OutboxHandlerAnnotation(id = "invalid", consumerGroup = "")
        fun invalid(payload: String) = Unit
    }
}
