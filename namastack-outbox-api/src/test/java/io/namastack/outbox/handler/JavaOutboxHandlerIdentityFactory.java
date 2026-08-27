package io.namastack.outbox.handler;

/** Creates handler identities through constructors exposed to Java callers. */
final class JavaOutboxHandlerIdentityFactory {

    private JavaOutboxHandlerIdentityFactory() {}

    static OutboxHandlerIdentity withId(String id) {
        return new OutboxHandlerIdentity(id);
    }
}
