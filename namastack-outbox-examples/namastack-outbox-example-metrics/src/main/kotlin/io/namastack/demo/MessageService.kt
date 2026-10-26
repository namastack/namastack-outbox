package io.namastack.demo

import io.namastack.outbox.Outbox
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class MessageService(
    private val outbox: Outbox,
) {
    @Transactional
    fun enqueue(count: Int, fail: Boolean) {
        repeat(count) {
            val message = DemoMessage(UUID.randomUUID().toString(), fail)
            outbox.schedule(payload = message, key = message.id)
        }
    }
}
