# ADR-0001: Rolling Deployment Compatibility Failures

- **Status:** Accepted
- **Date:** 2026-09-23
- **Decision owner:** @rolandbeisel
- **Discussion:** https://github.com/orgs/namastack/discussions/473
- **Issue:** https://github.com/namastack/namastack-outbox/issues/464
- **Implementation:** https://github.com/namastack/namastack-outbox/pull/470
- **Supersedes:** None

## Context

Namastack Outbox contains both sides of an asynchronous contract. Scheduling a record is the producing side; polling,
materializing, routing, and invoking a handler is the consuming side. During a rolling deployment, different application
versions perform these roles concurrently while sharing the same persisted outbox.

Every processing instance must therefore understand every record that any active producer can create. The supported
deployment model follows expand-and-contract:

1. Deploy the new payload type and handler to every processing instance while production remains disabled.
2. Wait until the rollout is complete and partition ownership has settled.
3. Enable production of the new record type.

The reverse sequence applies when removing a payload type or handler. Violating this contract can allow an older
instance to own a partition containing a record that requires application code available only in a newer version.

Two failures can then occur before handler delivery begins:

- the current instance cannot load the persisted payload type;
- the persisted handler ID is not registered on the current instance.

An unavailable payload type already fails during record materialization and does not consume a delivery retry. An
unavailable handler previously failed inside the primary processor's delivery error path, incremented `failureCount`,
and could permanently fail the record without invoking a handler.

The library must correct this inconsistent retry accounting and preserve the record safely. Compatibility handling is a
safeguard for a violated deployment contract; it is not intended to provide normal throughput or a second durable
processing lifecycle for an incompatible deployment.

## Requirements and Constraints

- Missing payload types and missing handler IDs are compatibility failures that occur before delivery.
- Compatibility failures do not increment `failureCount` or consume a handler retry policy.
- The affected record remains unchanged with status `NEW`.
- Processing stops immediately for the affected record key so later records for that key do not pass it.
- Other record keys already submitted in the current batch may complete normally.
- Actual exceptions thrown by an invoked handler continue through the existing retry, fallback, and permanent-failure
  chain.
- The solution introduces no database schema, persisted status, compatibility counter, or persisted retry mechanism.
- The solution does not change repository APIs or require compatibility-specific repository queries.
- JDBC, JPA, and MongoDB identify unavailable payload types consistently.
- The safeguard uses bounded, constant-size scheduler state and cannot grow with payload types, handlers, or record
  keys.
- The deployment contract and the compatibility behavior are documented.

The following concerns are outside this decision:

- arbitrary payload or context deserialization failures unrelated to unavailable payload types;
- poison-record management;
- lazy or metadata-first materialization;
- persisted management and healing of incompatible records;
- dedicated compatibility metrics or observations.

## Considered Options

### Option 1: Treat compatibility failures as delivery failures

Increment `failureCount` and use the normal handler retry lifecycle.

This reuses existing processing but can exhaust the delivery budget before a handler has been invoked. A missing handler
also cannot provide its handler-specific retry policy. A compatible instance may inherit a permanently failed record
that it would otherwise be able to process.

### Option 2: Maintain instance-local compatibility exclusions

Remember unavailable payload types, handler IDs, and record keys in memory and pass them to repository queries so
affected keys are not selected again by the same instance.

This preserves delivery retries and allows unrelated keys to continue. It also changes the public repository contract,
adds backend-specific query variants, complicates custom repositories, requires collection-capacity behavior, and can
starve compatible keys when bounded exclusions are evicted and rediscovered.

### Option 3: Persist an incompatible record state

Add an `INCOMPATIBLE` status or other persistent compatibility metadata and reactivate records through a healing process
when partition ownership changes.

This provides persisted visibility and exact record-level state. It also creates another record lifecycle, changes the
persisted protocol and public status model, requires healing and ownership semantics, affects every persistence
implementation, and introduces mixed-version behavior that is disproportionate to a deployment-contract violation.

### Option 4: Use lazy or metadata-first materialization

Load record metadata before resolving the payload and materialize records individually during processing.

This can allow compatible predecessors to complete before an incompatible record is encountered. It does not decide
retry accounting by itself and requires a broader repository-boundary redesign across JDBC, JPA, and MongoDB.

### Option 5: Preserve the record and pause the scheduler instance

Classify unavailable payload types and handlers explicitly, leave the record unchanged, and apply one short
compatibility cooldown to the scheduler instance before it queries another batch.

This avoids persistent state, exclusion collections, and repository changes. Its deliberate trade-off is a larger blast
radius: one incompatible record temporarily pauses polling for every partition owned by that scheduler instance.

## Decision

Adopt Option 5 for version 1.10.0.

### Failure classification

The persistence mappers throw `OutboxPayloadTypeNotFoundException` when the persisted payload class cannot be loaded
because of `ClassNotFoundException` or a linkage error. The exception retains the record metadata required for an
actionable diagnostic.

`OutboxHandlerInvoker` throws `OutboxHandlerNotFoundException` when the persisted handler ID is not registered. The
primary processor verifies handler availability before entering its delivery failure handling. An exception thrown by a
handler after invocation remains a delivery failure.

Payload deserialization failures do not activate this compatibility mechanism unless the serializer propagates a JVM
linkage failure showing that the payload type or one of its referenced types is unavailable. Serializer-specific
failures cannot be classified generically and retain their existing scheduler-level error handling, as do context and
other payload deserialization failures.

### Record-key behavior

The scheduler catches compatibility exceptions at the per-record-key boundary. It stops processing that key immediately
and leaves the affected record and its successors unchanged. This barrier applies independently of
`stop-on-first-failure` because no delivery attempt began and no record-level failure state was persisted.

Record-key tasks already submitted for the current batch continue normally. A compatibility failure does not cancel or
interrupt those tasks.

### Scheduler cooldown

When a compatibility failure occurs, the scheduler sets one instance-local cooldown deadline to 30 seconds after the
failure. Concurrent failures may extend the deadline but cannot shorten an existing cooldown.

Before loading another batch, the scheduler checks the deadline. While the cooldown is active, it performs no repository
query and reports no task result to the polling trigger. This prevents compatibility cooldowns from affecting adaptive
polling intervals. Once the deadline expires, the scheduler probes again.

The deadline is held only in memory and disappears when the process stops. A restarted or replacement instance therefore
reevaluates the unchanged record on its next polling cycle without inheriting the cooldown. The duration is an internal
constant in 1.10.0 rather than a public configuration property because it is a defensive safeguard, not a compatibility
retry policy.

### Diagnostics and instrumentation

A compatibility failure emits an actionable warning containing the available record ID, record key, payload type, and
handler ID. The warning states that the record remains pending, no delivery retry was consumed, and polling on the
current instance is temporarily paused.

This decision adds no dedicated metric or observation. Compatibility instrumentation will use the separately designed
instrumentation SPI rather than introducing an interim observability API.

## Rationale

The selected option fixes the correctness problem at its source: delivery retries represent actual handler invocations.
Missing application code is detected before delivery handling and cannot permanently fail a record.

A single cooldown timestamp is predictable, constant-size state. It prevents a short polling interval from producing
continuous database queries and warnings while avoiding repository API changes, backend-specific exclusion queries,
parameter limits, eviction behavior, and a second persisted lifecycle.

Pausing an incompatible scheduler instance can delay otherwise compatible keys. This is an acceptable
safety-over-availability trade-off after the application violates the documented producer-before-consumer compatibility
contract. Applications requiring uninterrupted processing during deployments must follow the expand-and-contract
sequence.

## Consequences

- Missing payload types and handlers receive consistent pre-delivery semantics.
- Compatibility failures leave status, payload, context, `failureCount`, `failureReason`, and `nextRetryAt` unchanged.
- Actual handler failures retain their existing retry and fallback behavior.
- No repository interface or built-in repository query changes are required.
- Custom repository implementations require no compatibility-specific changes.
- Scheduler state remains constant in size and requires no eviction policy.
- One incompatible record pauses new polling for all partitions owned by that scheduler instance for 30 seconds.
- Other keys already submitted in the current batch can complete.
- Repeated incompatibility produces at most one probe cycle per cooldown period, although several incompatible keys in
  the same batch may each produce a warning.
- A backlog can grow until the incompatible instance is replaced or the deployment is corrected.
- A permanently incompatible record requires operational or data correction.
- Arbitrary deserialization failures unrelated to unavailable payload types retain their previous behavior and may be
  retried on every polling cycle.
- Instances running an older Namastack Outbox version do not have this protection and must be upgraded before new record
  contracts are enabled.

## Follow-up Work

- Simplify PR #470 according to this decision.
- Update unit and integration tests for record preservation, delivery retry accounting, current-batch completion, and
  cooldown expiry.
- Update the rolling-deployment documentation to describe the instance-wide cooldown and its throughput trade-off.
- Expose compatibility diagnostics through the instrumentation SPI after that SPI is accepted and implemented.
- Consider poison-record handling or metadata-first materialization only when supported by a separate concrete
  requirement and decision.
