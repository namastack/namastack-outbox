package io.namastack.outbox.handler;

import org.jspecify.annotations.NonNull;

/** Compiles like a pre-stable-ID Java implementation: no identity methods required. */
final class LegacyJavaTypedHandler implements OutboxTypedHandler<String> {
    @Override
    public void handle(String payload, @NonNull OutboxRecordMetadata metadata) {
        // no-op
    }
}
