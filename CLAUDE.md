# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Namastack Outbox is a transactional outbox library for Spring Boot (Kotlin source, used from both Kotlin and Java). Records are persisted atomically with business data and delivered asynchronously by a partition-aware scheduler with retries, fallbacks, ordering per record key, and horizontal scaling across instances.

## Commands

Gradle multi-module build (Kotlin, JVM toolchain 17; CI also runs tests on 21 and 25).

```bash
./gradlew build                                   # compile, test, ktlint, aggregated jacoco report
./gradlew :namastack-outbox-core:test             # one module
./gradlew :namastack-outbox-core:test --tests "io.namastack.outbox.processor.FallbackOutboxRecordProcessorTest"
./gradlew :namastack-outbox-core:test --tests "*FallbackOutboxRecordProcessorTest.some test name*"
./gradlew :namastack-outbox-integration-tests:test  # Spring Boot + H2/Testcontainers (Docker needed for DB containers)
./gradlew ktlintCheck / ktlintFormat
```

- Outside CI, `build` is finalized by `publishToMavenLocal` (snapshot version from root `build.gradle.kts`).
- `namastack-outbox-examples/*` are **separate Gradle builds** (own `gradlew`) that consume the library from `mavenLocal()` via the BOM. Publish first (`./gradlew publishToMavenLocal`), then `cd` into an example and run `./gradlew test`. `run_smoke_tests.sh` runs them all.
- `namastack-outbox-docs/` is a Docusaurus site (`npm start`, `npm run build`); reference docs live in `docs/reference/`, with frozen copies in `versioned_docs/`.

## Module layout

- `namastack-outbox-api` — public user-facing API: `Outbox`, handler interfaces (`OutboxHandler`, `OutboxTypedHandler`, `*WithFallback`), annotations (`@OutboxHandler`, `@OutboxFallbackHandler`, `@OutboxRetryable`, `@OutboxEvent`), retry policies/backoff, routing selectors. Changes here are public-API changes (KDoc + compatibility matter).
- `namastack-outbox-core` — runtime, processing, handler discovery, partitioning, Spring auto-configuration. Defines persistence SPIs (`OutboxRecordRepository`, instance and `PartitionAssignmentRepository`).
- Persistence implementations: `-jdbc` (holds DDL in `src/main/resources/schema/<db>/`, used by JPA too), `-jpa`, `-mongodb`. `-starter-*` modules are dependency bundles.
- Integrations: `-kafka`, `-rabbit`, `-sns` (externalizing records to brokers), `-jackson` (payload serializer).
- Observability: `-observability-api` (Micrometer Observation conventions/docs), `-observability`, `-metrics`, `-tracing`, `-actuator`.
- `-integration-tests` — end-to-end Spring Boot tests (uses starter-jpa; Testcontainers for Postgres/MySQL/MariaDB/MSSQL/Oracle).
- `-bom`, `-performance-test` (not in `settings.gradle.kts`).

## Core architecture

**Runtime assembly.** `OutboxRuntimeFactory.create(OutboxRuntimeSpec)` builds one isolated, stopped `OutboxRuntime` from fully resolved inputs (persistence, handler infrastructure, resources, settings) with no Spring lookups. Spring auto-configurations in `core/config` (listed in `META-INF/spring/...AutoConfiguration.imports`) feed it in `OutboxRuntimeMode.SINGLE`; `CHANNELS` mode is supplied by an external module, which reuses the same configs via `META-INF/namastack/io.namastack.child.autoconfigure.imports`. Guard single-runtime-only beans with `@ConditionalOnSingleRuntimeMode`.

**Lifecycle ordering** (`SmartLifecycle` phases): `OutboxInstanceRegistry` (0, heartbeats) → `PartitionCoordinator` (1, partition assignment/rebalancing across live instances) → `OutboxProcessingScheduler` (2).

**Processing loop.** `OutboxProcessingScheduler.process()` runs per `OutboxPollingTrigger` (fixed or adaptive). Within `partitionCoordinator.withStableAssignments`, it loads record keys with `NEW` records in owned partitions, then processes each key on the `TaskExecutor`; records of one key are processed sequentially in order, stopping at the first one not completed (strict per-key ordering). Record key → partition via `PartitionHasher`.

**Processor chain** (chain of responsibility, built in `OutboxRuntimeFactory.createProcessorChain`):
`PrimaryOutboxRecordProcessor` (invoke handler) → `RetryOutboxRecordProcessor` (schedule retry per retry policy) → `FallbackOutboxRecordProcessor` (invoke fallback handler when retries are exhausted/not retryable) → `PermanentFailureOutboxRecordProcessor` (mark failed). `OutboxRecordProcessorChainInvoker` wraps the chain with instrumentation.

**Handler pipeline** (`core/handler`): discovery (`discovery/` — annotated methods and interface implementations, for both handlers and fallbacks, found by `OutboxHandlerBeanPostProcessor`) → validation and assembly (`assembly/HandlerRegistrationAssembler`, `HandlerMethodFactory`, `FallbackMatcher`, `HandlerRetryPolicyResolver`) → `registry/` (`OutboxHandlerRegistry`, `OutboxFallbackHandlerRegistry`) → `invoker/`. Handlers have stable identities (`identity/`) persisted on records so routing survives restarts and rolling deployments; records whose handler or payload type is unavailable on this instance trigger a compatibility cooldown rather than failure (see ADR 0001).

**Scheduling.** `OutboxService` implements `Outbox.schedule(...)`: resolves matching handlers, collects context (`OutboxContextProvider`s), and writes one record per handler within the caller's transaction. `multicaster/` optionally routes Spring application events annotated `@OutboxEvent` into the outbox.

**Instrumentation.** Core emits through the `OutboxInstrumentation` SPI (`core/instrumentation`); metrics/tracing/observability modules plug in implementations (see ADR 0002).

## Conventions

- Commit messages are prefixed with the GitHub issue: `GH-123 description`. Branches: `feature/...` or `fix/...`.
- Changes substantially affecting public API, persistence model, ordering/retry semantics, compatibility, or several modules require an ADR in `docs/adr/` (template `0000-template.md`, process in `GOVERNANCE.md`); the PR template asks for the ADR link or `Not required`.
- Public API needs KDoc with `@author` and `@since <version>` (current version is in root `build.gradle.kts`).
- Kotlin classes that Spring must proxy use `@OpenForProxy` (configured as an `allOpen` annotation) instead of `open`.
- Tests: JUnit 5, MockK, AssertJ; backtick-sentence test names. Java-interop scenarios are covered by Java test sources (`src/test/java`).
- Schema changes must be applied to every dialect under `namastack-outbox-jdbc/src/main/resources/schema/` and to MongoDB; integration tests validate schemas.
- User-facing behavior changes should update `namastack-outbox-docs/docs/reference/`.
- Reference docs and KDoc describe the current behavior, not what changed. Write for a reader who doesn't know earlier versions: avoid "existing", "still", "continues to", "no longer", "legacy", "old" and comparisons with previous behavior. Migration steps, breaking changes and removed APIs belong in release notes. Upgrade procedures stay only on pages about upgrading, written as steps.
- Mark when a feature became available with markers, not in the prose (no "Since 1.12.0, handlers can…"):
  - Section or page: an admonition directly under the heading:
    ```
    :::info Available since 1.12.0
    Processing groups are available since version 1.12.0.
    :::
    ```
  - Single item (list entry, code comment, YAML comment): a trailing `(since 1.12.0)`.
  - Passing mentions that link to the feature's page need no marker.
  - In KDoc, use `@since`; a property added to an existing class gets `(since X.Y.Z)` in its `@property` description.
