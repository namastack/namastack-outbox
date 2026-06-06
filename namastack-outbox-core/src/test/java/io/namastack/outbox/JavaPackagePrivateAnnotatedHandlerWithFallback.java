package io.namastack.outbox;

import io.namastack.outbox.annotation.OutboxFallbackHandler;
import io.namastack.outbox.annotation.OutboxHandler;
import io.namastack.outbox.handler.OutboxFailureContext;
import io.namastack.outbox.handler.OutboxRecordMetadata;

@SuppressWarnings("unused")
public class JavaPackagePrivateAnnotatedHandlerWithFallback {

    @OutboxHandler
    void handle(String payload, OutboxRecordMetadata metadata) {
    }

    @OutboxFallbackHandler
    void handleFailure(String payload, OutboxFailureContext context) {
    }
}
