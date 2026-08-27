package io.namastack.outbox.handler

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

@DisplayName("OutboxHandler")
class OutboxHandlerTest {
    @Test
    fun `identity configuration defaults retain generated identity without aliases`() {
        val identity = OutboxHandlerIdentity()

        assertThat(identity.id).isNull()
        assertThat(identity.aliases).isEmpty()
    }

    @Test
    fun `Java callers can configure an identity without specifying aliases`() {
        val identity = JavaOutboxHandlerIdentityFactory.withId("orders.publish-created")

        assertThat(identity.id).isEqualTo("orders.publish-created")
        assertThat(identity.aliases).isEmpty()
    }

    @Test
    fun `supports defaults to true`() {
        val handler =
            object : OutboxHandler {
                override fun handle(
                    payload: Any,
                    metadata: OutboxRecordMetadata,
                ) {
                    // no-op
                }
            }

        val supported = handler.supports("payload", metadata())

        assertThat(supported).isTrue()
    }

    @Test
    fun `identity configuration defaults preserve generated identity behavior`() {
        val handler =
            object : OutboxHandler {
                override fun handle(
                    payload: Any,
                    metadata: OutboxRecordMetadata,
                ) = Unit
            }

        assertThat(handler.getGenericHandlerIdentity()).isNull()
    }

    @Test
    fun `typed identity configuration defaults preserve generated identity behavior`() {
        val handler =
            object : OutboxTypedHandler<String> {
                override fun handle(
                    payload: String,
                    metadata: OutboxRecordMetadata,
                ) = Unit
            }

        assertThat(handler.getTypedHandlerIdentity()).isNull()
    }

    @Test
    fun `legacy Java typed handler inherits identity defaults`() {
        val handler = LegacyJavaTypedHandler()

        assertThat(handler.getTypedHandlerIdentity()).isNull()
    }

    private fun metadata() =
        OutboxRecordMetadata(
            key = "test-key",
            handlerId = "test-handler",
            createdAt = Instant.now(),
            context = emptyMap(),
        )
}
