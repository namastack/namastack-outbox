package io.namastack.outbox;

import io.namastack.outbox.annotation.OutboxHandler;
import io.namastack.outbox.handler.OutboxRecordMetadata;

@SuppressWarnings("unused")
public class JavaPackagePrivateAnnotatedHandler {

    @OutboxHandler
    void handle(String payload, OutboxRecordMetadata metadata) {
    }
}
