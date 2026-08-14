# Spring Modulith issue 1777: stable handler identifiers

Namastack stores a handler identifier on every outbox record so that the record can be routed back
to the correct handler. Its default identifier contains the handler's concrete class and method
signature. That is a useful zero-configuration default, but a refactoring or a generated framework
handler class can make the identifier unstable while older records still refer to the previous one.

The non-breaking solution is an optional default method on `OutboxHandler` and
`OutboxTypedHandler`. Existing handlers inherit `getHandlerId() = null` and keep the generated
identifier. An integration that owns a long-lived logical handler can override it with an identifier
that is independent of its implementation:

```java
final class ModulithEventExternalizer implements OutboxHandler {

    @Override
    public String getHandlerId() {
        return "spring-modulith-event-externalizer";
    }
}
```

The fallback variants inherit the same default method from `OutboxHandler` or
`OutboxTypedHandler`; they do not need another identifier contract. A bean that only happens to
declare a similarly named method is intentionally not treated as a handler—the ID is read only after
the bean has been discovered through one of the existing handler interfaces.

Annotation-based handlers can set the identifier per method, which also supports multiple handlers
on the same bean:

```java
@OutboxHandler(id = "spring-modulith-event-externalizer")
void externalize(Object event, OutboxRecordMetadata metadata) {
    // ...
}
```

Previous identifiers can also be declared explicitly when a handler was renamed:

```java
@OutboxHandler(
    id = "spring-modulith-event-externalizer",
    aliases = { "com.example.PreviousHandler#handle(java.lang.Object)" }
)
```

Spring Modulith can implement this interface on its Namastack handler without changing either
existing `OutboxHandler` implementations or the scheduling API. Namastack continues registering the
previous generated identifier as a legacy alias, allowing records created before the integration
adopts the stable identifier to drain normally. New records persist the explicit stable identifier.
For proxied handlers, both the generated target-class identifier and the historical runtime
proxy-class identifier are registered as aliases. Configured and automatically generated aliases
share the same global uniqueness constraint as canonical handler identifiers.

The configured identifier must be non-blank. If two handlers use the same identifier, application
startup fails with an error that names the duplicated ID and both handler methods. Registration
reserves the ID before changing any type indexes, so a rejected handler is never partially
registered. This is preferable to silently routing both sets of persisted records to whichever
handler happened to register last.
