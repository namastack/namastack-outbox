package io.namastack.outbox.handler;

import org.jspecify.annotations.NonNull;

/**
 * Java bean implementing both fallback interfaces to verify that the disposition defaults do not
 * conflict and can be overridden individually.
 */
public class JavaHandlerWithFallbackDispositions
        implements OutboxTypedHandlerWithFallback<Object>, OutboxHandlerWithFallback {

    @Override
    public void handle(@NonNull Object payload, @NonNull OutboxRecordMetadata metadata) {
    }

    @Override
    public void handleFailure(@NonNull Object payload, @NonNull OutboxFailureContext context) {
    }

    @Override
    public @NonNull OutboxFallbackDisposition getTypedFallbackDisposition() {
        return OutboxFallbackDisposition.FAIL;
    }
}
