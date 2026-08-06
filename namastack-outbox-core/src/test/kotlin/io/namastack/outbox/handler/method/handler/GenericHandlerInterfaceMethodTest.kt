package io.namastack.outbox.handler.method.handler

import io.namastack.outbox.handler.OutboxHandler
import io.namastack.outbox.handler.OutboxRecordMetadata
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

@DisplayName("GenericHandlerInterfaceMethod")
class GenericHandlerInterfaceMethodTest {
    @Test
    fun `uses explicitly supplied stable handler ID`() {
        val bean = IdentifiedGenericHandler("spring-modulith-event-externalizer")
        val method =
            bean::class.java.getMethod(
                "handle",
                Any::class.java,
                OutboxRecordMetadata::class.java,
            )

        val handler = GenericHandlerInterfaceMethod(bean, method)

        assertThat(handler.id).isEqualTo("spring-modulith-event-externalizer")
        assertThat(handler.aliases)
            .contains("previous-externalizer")
            .anyMatch { it.contains(IdentifiedGenericHandler::class.java.name) }
    }

    @Test
    fun `rejects a blank explicitly supplied handler ID`() {
        val bean = IdentifiedGenericHandler(" ")
        val method =
            bean::class.java.getMethod(
                "handle",
                Any::class.java,
                OutboxRecordMetadata::class.java,
            )

        assertThatIllegalArgumentException()
            .isThrownBy { GenericHandlerInterfaceMethod(bean, method) }
            .withMessage("Outbox handler ID must not be blank")
    }

    @Test
    fun `supportsScheduling delegates to OutboxHandler supports`() {
        val bean = ConditionalGenericHandler(supported = false)
        val method =
            bean::class.java.getMethod(
                "handle",
                Any::class.java,
                OutboxRecordMetadata::class.java,
            )
        val handler = GenericHandlerInterfaceMethod(bean, method)

        val supported = handler.supportsScheduling("payload", metadata())

        assertThat(supported).isFalse()
    }

    private fun metadata() =
        OutboxRecordMetadata(
            key = "test-key",
            handlerId = "test-handler",
            createdAt = Instant.now(),
            context = emptyMap(),
        )

    private class ConditionalGenericHandler(
        private val supported: Boolean,
    ) : OutboxHandler {
        override fun supports(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ): Boolean = supported

        override fun handle(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) {
            // no-op
        }
    }

    private class IdentifiedGenericHandler(
        private val handlerId: String,
    ) : OutboxHandler {
        override fun getHandlerId(): String = handlerId

        override fun getHandlerAliases(): Set<String> = setOf("previous-externalizer")

        override fun handle(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) {
            // no-op
        }
    }
}
