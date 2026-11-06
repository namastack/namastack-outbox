# Brief 01 — `outbox_record` usage map

You are a sub-agent. The orchestrator gives you: the worktree to inspect, and the output file.

## Isolation rule

Never open, list, grep, glob or summarize anything under `docs/db/` (this includes `docs/db/**` in any
worktree). Exclude it from every search (`grep --exclude-dir=db`, `git diff ... -- . ':!docs'`). Do not
use commit messages, PR texts or docs as the rationale for v1 changes; judge v1 only by its code. If
you accidentally read something from `docs/db/`, stop and report it.

## Task

Read code only (Kotlin/Java sources, DDL, tests). Focus on `OutboxRecordRepository`
(`namastack-outbox-core/src/main/kotlin/io/namastack/outbox/OutboxRecordRepository.kt`) and its JDBC
and JPA implementations. Ignore MongoDB.

Write a markdown report with these sections:

1. **Table.** Columns of `outbox_record` per dialect (`namastack-outbox-jdbc/src/main/resources/schema/*/outbox-tables.sql`),
   types, nullability, all indexes and constraints, and the JPA entity mapping.
2. **Field semantics and relations.** What each column means and how they relate: the status state
   machine (who sets NEW / COMPLETED / FAILED and when), `next_retry_at`, failure count / failure
   fields, `completed_at`, `created_at`, `record_key` and ordering, partition column and
   `PartitionHasher`, handler id. Invariants (e.g. which columns are set for which status).
   Include a small state diagram.
3. **Method catalog.** For every method of `OutboxRecordRepository`:
   - purpose, callers (file:line), trigger (per poll, per key, per record, retention schedule, metrics
     scrape, actuator/admin, user API), configuration that enables it, estimated frequency for a
     typical deployment (state assumptions);
   - the exact SQL per JDBC dialect (resolve string templates; note dialect branches) and the JPA
     JPQL/Criteria plus the expected Hibernate SQL;
   - parameters and result size, transaction/locking behavior;
   - which existing index can serve it, and the likely access path.
4. **Grouping by purpose.** Hot processing path, writes (create/save/update), monitoring and metrics,
   retention/cleanup, admin/listing. Per group: methods, frequency, why it matters.
5. **Write amplification.** Which columns change on each update, which indexes each update touches.
6. **Open questions** for the orchestrator.

If the orchestrator assigned you the **v1 delta**, write instead: every query, DDL, index and caller
change between `main` and the v1 worktree (`git diff main..e0b09b4 -- . ':!docs'`), each as one
independently revertible item (id, description, files, affected methods, dialects). Describe what the
code does, not why someone may have chosen it.

Return a summary of ≤300 words with the output path.
