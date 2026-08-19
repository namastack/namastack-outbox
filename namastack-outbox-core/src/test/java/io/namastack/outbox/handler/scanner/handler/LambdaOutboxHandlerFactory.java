package io.namastack.outbox.handler.scanner.handler;

import io.namastack.outbox.handler.OutboxHandler;
import io.namastack.outbox.handler.OutboxTypedHandler;

final class LambdaOutboxHandlerFactory {

    private LambdaOutboxHandlerFactory() {}

    static OutboxHandler create() {
        return (payload, metadata) -> {};
    }

    static OutboxTypedHandler<String> createTyped() {
        return (payload, metadata) -> {};
    }
}
