package io.namastack.performance.consumer

import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.OutboxTypedHandler
import org.springframework.stereotype.Component

@Component
class PaymentRequestedEventHandler : OutboxTypedHandler<PaymentRequestedEvent> {
    override fun handle(
        payload: PaymentRequestedEvent,
        metadata: OutboxRecordMetadata,
    ) {
        // Intentionally empty: this benchmark isolates outbox processing overhead.
    }

    companion object {
        const val HANDLER_ID =
            "io.namastack.performance.consumer.PaymentRequestedEventHandler" +
                "#handle(io.namastack.performance.consumer.PaymentRequestedEvent," +
                "io.namastack.outbox.handler.OutboxRecordMetadata)"
    }
}

