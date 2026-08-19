package io.namastack.outbox.handler;

final class LambdaOutboxHandlerFactory {

    private LambdaOutboxHandlerFactory() {}

    static OutboxHandler create() {
        return (payload, metadata) -> {};
    }
}
