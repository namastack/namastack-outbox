package io.namastack.outbox.handler

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

@DisplayName("OutboxHandler")
class OutboxHandlerTest {
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

    private fun metadata() =
        OutboxRecordMetadata(
            key = "test-key",
            handlerId = "test-handler",
            createdAt = Instant.now(),
            context = emptyMap(),
        )
}
