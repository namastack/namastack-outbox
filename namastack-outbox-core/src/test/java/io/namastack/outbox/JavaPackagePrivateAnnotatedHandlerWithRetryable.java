package io.namastack.outbox;

import io.namastack.outbox.annotation.OutboxHandler;
import io.namastack.outbox.annotation.OutboxRetryable;
import io.namastack.outbox.handler.OutboxRecordMetadata;

@SuppressWarnings("unused")
public class JavaPackagePrivateAnnotatedHandlerWithRetryable {

    @OutboxHandler
    @OutboxRetryable(name = "CustomerOutboxRetryPolicy")
    void handle(String payload, OutboxRecordMetadata metadata) {
    }
}
