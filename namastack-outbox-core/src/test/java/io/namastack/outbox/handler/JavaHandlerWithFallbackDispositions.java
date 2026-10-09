package io.namastack.outbox.handler;

/**
 * Java bean implementing both fallback interfaces to verify that the disposition defaults do not
 * conflict and can be overridden individually.
 */
public class JavaHandlerWithFallbackDispositions
        implements OutboxTypedHandlerWithFallback<Object>, OutboxHandlerWithFallback {

    @Override
    public void handle(Object payload, OutboxRecordMetadata metadata) {
    }

    @Override
    public void handleFailure(Object payload, OutboxFailureContext context) {
    }

    @Override
    public OutboxFallbackDisposition getTypedFallbackDisposition() {
        return OutboxFallbackDisposition.KEEP_FAILED;
    }
}
