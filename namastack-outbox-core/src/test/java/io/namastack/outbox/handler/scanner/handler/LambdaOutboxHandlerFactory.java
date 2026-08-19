package io.namastack.outbox.handler.scanner.handler;

import io.namastack.outbox.handler.OutboxHandler;

final class LambdaOutboxHandlerFactory {

    private LambdaOutboxHandlerFactory() {}

    static OutboxHandler create() {
        return (payload, metadata) -> {};
    }
}
