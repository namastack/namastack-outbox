# ADR-0004: Processing Groups Without a Schema Migration

- **Status:** Accepted
- **Date:** 2026-10-28
- **Decision owner:** @rolandbeisel
- **Discussion:** Design agreed with the maintainer; no separate GitHub Discussion recorded
- **Related:** https://github.com/namastack/namastack-outbox/issues/527,
  https://github.com/namastack/namastack-outbox/pull/528
- **Supersedes:** None

## Context

Namastack creates a separate delivery record for every matching handler. Previously,
all deliveries sharing a record key shared one serialization and failure boundary.
Applications need some handlers to share that boundary while allowing others to
process the same business key independently, including across different event types.

The feature must work with existing JDBC, JPA and MongoDB repositories and schemas.
Application handlers and messaging integrations must continue receiving the original
business key rather than an internal storage representation.

## Requirements and Constraints

- Every matching handler receives its own delivery; groups are not competing subscriptions.
- Serialization and strict failure blocking apply to `(runtime, processing group, business key)`.
- Different handlers in the same group share that boundary; different groups and keys can
  progress independently within shared worker and database resource limits.
- Grouped scheduling is opt-in, with `default` preserving ordinary unencoded keys.
- Existing persisted group assignments survive restarts, handler changes and disabling new
  grouped writes.
- No schema migration, additional group index, new repository contract or global registry.
- No business-event causality, producer commit-order or exactly-once delivery guarantee.

## Considered Options

### Dedicated processing-group column

Store the business key and group separately and extend repository queries, partitioning
and indexes to use both. This avoids an encoding convention and its ambiguity, but
requires coordinated schema and repository changes across persistence implementations.

### Processing group in record context

Persist the group as context metadata. Existing key queries and partitioning would
still group unrelated consumers together unless repositories learned to query context.
That introduces persistence-specific query and index changes.

### Encode the group in the existing key

Use the existing stored key as the serialization identity. Existing repository queries,
indexes and partition hashing then operate on each group independently. The cost is a
reserved format, reduced available key length for grouped records and an unavoidable
collision with an identical freely chosen legacy key.

## Decision

Encode non-default groups as `~ns.pg1~<group>:<business key>`. Default-group keys remain
unencoded. Group names are validated at handler registration and contain 1–64 lowercase
ASCII letters, digits, dots, underscores or hyphens, starting with a letter or digit.
They cannot contain the separator `:`. Grouped storage keys are limited to 255 UTF-8 bytes.

`OutboxRecord.key` remains the exact storage key. Partition hashing, repository operations
and Actuator deletion continue using it. Add derived `businessKey` and `processingGroup`
properties, decoded once when constructing a record, including through its builder or
`restore()`. Do not change persistence entities or constructor signatures to store those
properties separately.

Primary and fallback metadata use the derived values. Record-attempt and handler
observations expose the business key and the persisted group separately. The group is
a high-cardinality observation attribute, without adding timer metric dimensions.
Diagnostics label business key, group and storage key explicitly. Missing-payload-type
exceptions decode their own stored key because no materialized record exists yet.

Decoding is independent of the grouped-scheduling flag. Only canonical version-1 keys
are decoded; malformed, non-canonical and unknown-version keys remain default-group
business keys. Decoding validates the stored parts without re-encoding the string.

## Rationale

The encoded identity reuses the existing serialization, retry and failure-blocking
mechanisms without a database migration. Separating its application-facing interpretation
on the record prevents each consumer of the model from decoding independently and keeps
messaging keys and trace correlation consistent.

## Consequences

- Grouped scheduling must remain disabled until every worker sharing an outbox understands
  the encoding. Deployment tooling verifies this; Namastack does not negotiate capabilities.
- New business keys with the reserved prefix are rejected when grouped scheduling is enabled.
  With it disabled, existing scheduling behavior is retained. A legacy or newly supplied
  plain key exactly matching the canonical format will therefore be decoded, even with
  grouped scheduling disabled. This collision is explicitly accepted.
- Changing a handler's group affects new records only. Pause producers and drain affected
  records before changing groups when uninterrupted ordering is required. Handler aliases
  preserve routing, not group membership.
- Ordinary default-group installations retain their stored keys and ordering boundary.
  Grouped keys consume additional column space. Raw queries and Actuator deletion use the
  encoded storage key; exposing derived properties does not change those contracts.
- Record order is ascending `createdAt`, then `id`, among available records. Equal timestamps
  and UUID tie-breaking do not establish handler priority or transaction commit order.
- Strict failures block only the affected group and business key. A successful fallback
  completes its delivery and releases that boundary. Shared executor and database limits
  still affect throughput across boundaries.
- Existing metadata getters remain available. Metadata uses the current data-class constructors
  and generated `copy` methods without legacy compatibility overloads. Manual construction
  and copying intentionally change their public signatures: existing Java calls must supply
  all current fields, including the processing group, and previously compiled callers must
  be rebuilt. Ordinary Kotlin constructor and named `copy` calls use default arguments;
  inferred constructor or `copy` function references may need explicit types or a lambda.
  This release-specific compatibility decision does not establish a general update policy.
- Delivery remains at least once. Processing Groups provide no inbox, deduplication or
  shared transaction around handler execution and source-record completion.

## Follow-up Work

- Define and publish the Namastack support and update policy separately after the feature merge.
- Align artifact versions with the intended 1.12.0 release before publishing the feature.
- A future group-aware administrative API can expose business-key and group filters while
  retaining explicit storage-key operations.
