package io.namastack.outbox.handler;

/** Creates a Java lambda fixture whose generated class name is not a stable routing identity. */
final class LambdaOutboxHandlerFactory {

    private LambdaOutboxHandlerFactory() {}

    static OutboxHandler create() {
        return (payload, metadata) -> {};
    }
}
