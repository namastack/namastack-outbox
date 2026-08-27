package io.namastack.outbox.handler;

import java.util.function.BiConsumer;

/** Creates a genuine Java SAM lambda for integration testing its Spring bean-name identity. */
public final class JavaLambdaOutboxHandlerFactory {
    private JavaLambdaOutboxHandlerFactory() {}

    public static OutboxHandler create(BiConsumer<Object, OutboxRecordMetadata> invocation) {
        return invocation::accept;
    }
}
