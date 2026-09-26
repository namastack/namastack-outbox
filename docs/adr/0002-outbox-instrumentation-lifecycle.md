# ADR-0002: Outbox Instrumentation Lifecycle

- **Status:** Proposed
- **Date:** 2026-09-26
- **Decision owner:** @rolandbeisel, @Alek96
- **Discussion:** https://github.com/orgs/namastack/discussions/474
-
**Issues:** https://github.com/namastack/namastack-outbox/issues/449, https://github.com/namastack/namastack-outbox/issues/467
- **Related:** ADR-0001 Rolling Deployment Compatibility Failures
- **Supersedes:** None

## Context

Namastack Outbox performs work on both sides of the transactional outbox boundary. An application schedules and persists
records, and a scheduler later loads, materializes, and processes them asynchronously.

The existing Micrometer integration observes public scheduling calls and individual primary or fallback handler
invocations. Persisted trace context reconnects a handler invocation to the request that scheduled the record. Core work
outside the handler call, including handler resolution, retry and fallback decisions, completion, deletion, and
permanent-failure persistence, does not run inside that record trace. Core logs can therefore use the technical
Processing
Scheduler trace or have no active trace context. This is the correlation gap described by issue #467.

`OutboxProcessingScheduler` already runs inside Spring's scheduled-task observation through `ScheduledMethodRunnable`.
That observation represents a technical polling cycle. It selects record keys, submits one task per key, and waits for
those tasks. With executor context propagation enabled, loading and materialization logs inherit the scheduler context;
without it, those executor threads have no active trace context. Processing errors are caught and logged by Core and do
not normally escape to mark the scheduler observation as failed.

The scheduler observation and an individual record trace serve different purposes. The scheduler observation describes
technical polling work. The record trace connects the producer request, scheduling operation, record-processing
attempts,
and handler invocations.

The observability implementation previously relied on Spring AOP. A public instrumentation SPI needs stable lifecycle
boundaries that Core can invoke directly, without depending on Spring or Micrometer and without exposing the internal
processor-chain design as public API.

## Requirements and Constraints

- Core exposes explicit, framework-neutral instrumentation boundaries without Spring AOP.
- The Micrometer observability module implements the Core SPI.
- Existing `outbox.record.schedule` and `outbox.record.process` observations remain compatible.
- A new observation covers the complete processing decision for one materialized, ready record.
- Primary and fallback handler calls remain distinguishable child observations.
- Only a handler that is actually invoked produces a handler observation.
- Handled retries, fallback completion, terminal failure, compatibility deferral, and unexpected errors have stable
  observable outcomes.
- Loading and materialization have one predictable boundary and never require a partial `OutboxRecord`.
- Instrumentation receives immutable, stable metadata and stored context where required, but never a payload value.
- Record IDs, record keys, payload types, exception types, and exception messages are not implicit low-cardinality
  metric
  dimensions.
- Instrumentation remains observational and does not own retry, fallback, ordering, compatibility, or persistence logic.
- Multiple instrumentation implementations compose in a defined order with minimal overhead when none are configured.
- Polling, partition coordination, batch coordination, executor submission, and individual processor stages do not
  become
  public SPI operations.
- Existing documented Micrometer convention extension points remain available.

The standard Outbox implementation and Core processing components invoke the SPI. Replacing those infrastructure
components is outside the supported extension model; a replacement must invoke the SPI itself if it wants equivalent
instrumentation.

## Considered Options

### Option 1: Keep handler-only record instrumentation

Retain scheduling, Processing Scheduler, and handler observations without adding a record-level operation.

This preserves existing telemetry but leaves handler resolution, retry and fallback coordination, completion,
permanent-failure handling, and related Core logs outside the persisted record trace. It does not close issue #467 and
cannot expose the final result of a processing attempt.

### Option 2: Observe one complete attempt for a materialized, ready record

Start a record-attempt operation after successful materialization and the retry-readiness check. Keep it active across
the
complete processing decision and use actual primary and fallback handler invocations as children.

Loading and materialization remain part of the technical Processing Scheduler execution. The record SPI never operates
on
a partial record or conditionally restores a trace from partially readable data.

### Option 3: Create short attempts for some materialization failures

Create a record-attempt operation after materialization fails when enough metadata and stored context remain readable.

This exposes more record-level failure telemetry, but gives the attempt operation different meanings depending on which
parts of an invalid row can still be read. It also requires a second invocation model for incomplete records and creates
conditional trace behavior for similar failures.

### Option 4: Expose every processing stage

Add public callbacks for loading, materialization, handler resolution, failure recording, retry decisions, fallback
coordination, completion, and permanent failure.

This offers detailed instrumentation but couples the public API to the current processor-chain implementation. Routine
internal refactoring would become an API compatibility concern.

## Decision

Adopt Option 2.

Core exposes three stable operations through `OutboxInstrumentation`:

1. `schedule`: one public scheduling call;
2. `processRecord`: one complete processing attempt for a materialized, ready record;
3. `invokeHandler`: one actual primary or fallback handler invocation.

Default methods invoke the supplied action without additional behavior. Multiple implementations are composed in Spring
order, with the first implementation acting as the outermost interceptor.

### Scheduling boundary

A scheduling operation begins when a public `schedule(...)` call enters the standard `Outbox` implementation and ends
after handler discovery, record creation, and persistence complete or throw. One call creates one operation even when it
persists records for multiple handlers.

The scheduling invocation exposes the payload type, logical record key, and channel. It does not expose the payload
object. Overloads that generate a persisted key report a stable `auto-generated` marker rather than exposing a generated
value as a metric dimension.

### Record-attempt boundary

A record attempt starts only after:

1. the persisted record was materialized successfully; and
2. its retry timestamp says it is ready for processing.

A record that is not ready creates no attempt. Once started, the attempt covers:

- persisted-handler resolution;
- primary handler invocation;
- delivery-failure recording;
- retry evaluation and retry-time persistence;
- fallback resolution and invocation;
- completion or deletion;
- terminal-failure persistence.

The normal Core result has one of three outcomes:

| Outcome           | Meaning                                                             | Expected persisted state       |
|-------------------|---------------------------------------------------------------------|--------------------------------|
| `COMPLETED`       | Primary or fallback handling completed and the result was persisted | `COMPLETED` or deleted         |
| `RETRY_SCHEDULED` | Delivery failed and another attempt was persisted                   | `NEW` with a future retry time |
| `FAILED`          | The terminal failure decision was persisted                         | `FAILED`                       |

The Micrometer implementation additionally classifies exceptions escaping the processing action:

| Outcome                  | Meaning                                                                   | Expected persisted state      |
|--------------------------|---------------------------------------------------------------------------|-------------------------------|
| `COMPATIBILITY_DEFERRED` | A materialized record's persisted handler is unavailable on this instance | `NEW`, retry budget unchanged |
| `ERROR`                  | An unexpected exception prevented a reliable normal result                | State may be uncertain        |

These exceptional classifications are not members of the Core `OutboxRecordProcessingOutcome` enum. The processing
action still throws the original exception. Micrometer maps an escaping `OutboxHandlerNotFoundException` to
`COMPATIBILITY_DEFERRED` and every other escaping exception to `ERROR`. `UNKNOWN` is an internal initial observation
value used only until processing returns or throws.

A successful fallback produces `COMPLETED`. Primary and fallback child observations explain how that result was reached.
Every attempt snapshots its one-based delivery-attempt number at entry. The primary and fallback invoked within that
attempt report the same number even when `failureCount` changes during processing.

### Loading and materialization

Record loading, payload-type resolution, payload deserialization, and stored-context deserialization happen before the
record-attempt boundary. Their failures do not create `outbox.record.attempt` observations or outcomes. Core logs them
on
the Processing Scheduler side and retains the processing semantics defined for those failures.

A missing handler differs because a complete record has entered the processing chain. It produces
`COMPATIBILITY_DEFERRED` without consuming the delivery retry budget and without creating a handler child observation.
Dedicated telemetry for pre-attempt materialization failures requires a separate decision.

### Handler-invocation boundary

A handler operation wraps only the call to an already resolved primary or fallback handler. Payload validation, handler
resolution, failure lookup, and handler-context construction happen before this boundary. If any preparation step fails,
no `outbox.record.process` observation is created.

A handler that is invoked and throws still produces a handler observation with that error. Fallback and primary
invocations are distinguished by stable handler-kind metadata.

### Instrumentation data contract

Invocation objects expose only the immutable metadata required to instrument their operation.

Scheduling metadata consists of:

- payload type;
- record key or the `auto-generated` marker;
- channel.

Record-attempt metadata consists of:

- record ID and key;
- persisted payload type;
- handler ID;
- snapshotted delivery attempt;
- channel;
- an immutable snapshot of stored context.

Handler metadata consists of:

- record ID and key;
- handler ID and handler kind;
- the attempt's snapshotted delivery attempt;
- channel.

Invocation objects do not expose the payload value or a complete `OutboxRecord`, because the record provides indirect
access to the payload. Normal completion returns the Core processing outcome. On exceptional completion, the original
exception remains available to instrumentation and propagates unchanged. Exception messages and stack traces may be
recorded by tracing implementations but are never metric dimensions.

### Observational contract and composition

Each instrumentation callback must:

- invoke its action exactly once;
- return the action's result unchanged;
- rethrow the action's original failure unchanged;
- avoid changing record state or making retry, fallback, compatibility, ordering, or persistence decisions.

Core does not isolate an implementation that violates this contract by throwing its own exception or failing to invoke
the action. Such a failure propagates and can fail the underlying operation. Instrumentation implementations must
therefore keep their own observation and reporting paths reliable.

Spring discovers every `OutboxInstrumentation` bean and composes them using `Ordered` or `@Order`. Adding custom
instrumentation does not disable the Micrometer implementation. The first ordered instrumentation is outermost.

### Trace relationships

The technical polling trace and each persisted record trace remain separate:

```text
Technical polling trace
└── Processing Scheduler span
    ├── processing task for key 1 (propagated context, no new span)
    ├── processing task for key 2 (propagated context, no new span)
    └── loading / materialization logs

Record trace
Application / producer span
└── outbox.record.schedule
    ├── outbox.record.attempt (deliveryAttempt = 1, RETRY_SCHEDULED)
    │   └── outbox.record.process (primary, error, deliveryAttempt = 1)
    └── outbox.record.attempt (deliveryAttempt = 2, COMPLETED)
        ├── outbox.record.process (primary, error, deliveryAttempt = 2)
        └── outbox.record.process (fallback, success, deliveryAttempt = 2)
```

Each retry restores the same persisted scheduling context and creates a sibling attempt. No span remains open during a
retry delay or across a restart. The attempt restores the stored propagation context. Handler observations use the
active
attempt as their parent instead of extracting the stored context again.

### Micrometer compatibility

The Micrometer implementation preserves the established observations:

- `outbox.record.schedule` remains one scheduling operation;
- `outbox.record.process` remains one actual primary or fallback handler invocation;
- `outbox.record.attempt` is additive and represents the complete materialized-record attempt.

The documented process customization API remains available under its established names:

- `OutboxProcessObservationContext`;
- `OutboxProcessObservationConvention`;
- `OutboxObservationDocumentation.DefaultOutboxProcessObservationConvention`.

Their documented constructor, handler-kind enum, getters, convention lookup, and low- and high-cardinality customization
model remain available. The attempt observation has its own context and convention types.

Default dimensions are divided by cardinality:

| Observation              | Low cardinality                   | High cardinality                        |
|--------------------------|-----------------------------------|-----------------------------------------|
| `outbox.record.schedule` | channel                           | record key, payload type                |
| `outbox.record.attempt`  | outcome, channel                  | record ID, record key, delivery attempt |
| `outbox.record.process`  | handler kind, handler ID, channel | record ID, record key, delivery attempt |

The attempt context is the Micrometer receiver context that restores persisted propagation data. The process context is
a
child context. Applications using the documented process getters and conventions remain compatible. Code depending on
the process context specifically being a `ReceiverContext` must migrate to the attempt context for propagation behavior.

## Rationale

The chosen boundary gives `outbox.record.attempt` one clear meaning: Core started processing a complete record. It
closes
the log-correlation gap and exposes a final processing result without making internal processors part of the public API.

Keeping loading and materialization on the scheduler side avoids conditional tracing based on partially readable data.
Keeping the scheduler observation separate preserves the distinction between technical polling and the business trace
that began when the record was scheduled.

Three coarse operations are sufficient for metrics, tracing, logging, and custom instrumentation while remaining stable
across processor and persistence refactoring. Metadata-only invocation objects reduce accidental disclosure and keep the
SPI suitable for third-party instrumentation.

## Consequences

- Every materialized, ready record gains one outer attempt observation and timer.
- Existing handler observations become children of the attempt while retaining their established names and dimensions.
- Record-processing Core logs use the persisted record trace while an attempt is active.
- Retries appear as sibling attempts under the original scheduling context.
- Missing handlers produce a compatibility-deferred attempt without a handler observation.
- Loading and materialization failures retain scheduler logs but have no record-attempt outcome.
- Existing process convention beans and cardinality customizations continue to be discovered.
- The process context no longer owns propagation extraction; advanced code relying on its former `ReceiverContext`
  inheritance requires migration.
- The processor chain must provide a stable normal result for completed, retried, and terminally failed attempts.
- Custom instrumentation can coexist with Micrometer and adds one ordered wrapper at each enabled boundary.
- Instrumentation implementations that violate the observational contract can disrupt outbox processing.

## Follow-up Work

- Restore the established `OutboxProcessObservationContext`, `OutboxProcessObservationConvention`, and default
  convention
  names in the implementation.
- Replace payload-bearing invocation fields and complete `OutboxRecord` references with immutable metadata snapshots.
- Move payload validation, handler resolution, and context construction outside `invokeHandler`.
- Snapshot one delivery-attempt value for the outer attempt and all handler children, including fallback.
- Add tests proving that preparation failures create no handler observation and fallback uses the parent's attempt
  number.
- Document the final SPI metadata, ordering contract, observation names, attributes, outcomes, and trace relationships.
- Revisit dedicated materialization-failure telemetry only if scheduler logs prove insufficient.
