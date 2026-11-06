# Brief 05 — Port and validate on all dialects

You are a sub-agent. The orchestrator gives you: the v2 worktree (`../outbox-wt-exp-v2`), the list of
changes in v2 (from the experiment reports), and the dialects to handle.

## Isolation rule

Never open, list, grep, glob or summarize anything under `docs/db/` (this includes `docs/db/**` in any
worktree). Exclude it from every search. If you accidentally read something from `docs/db/`, stop and
report it.

## Task

1. **Port.** Apply each v2 change to every dialect under
   `namastack-outbox-jdbc/src/main/resources/schema/` (h2, postgres, mysql, mariadb, oracle, sqlserver)
   and to the dialect-specific query code in `namastack-outbox-jdbc` and to `namastack-outbox-jpa`.
   Where a dialect lacks a feature (partial indexes, `INCLUDE`, `LATERAL`, `SKIP LOCKED`, etc.), choose
   the closest equivalent and document the choice. Do not touch MongoDB.
2. **Tests.** Run `./gradlew :namastack-outbox-jdbc:test :namastack-outbox-jpa:test` and, if schemas
   changed, `./gradlew :namastack-outbox-integration-tests:test` in the v2 worktree. Report failures
   with output.
3. **Measure** (only when the orchestrator says so, serially, never in parallel with other
   benchmarks): baseline vs v1 vs v2 on the requested dialects and scenarios with the harness, into
   `docs/db-postgresql/results/v2/<dialect>/<scenario>/`, plus equivalence checks against baseline.
4. **Report** a section per dialect for `docs/db-postgresql/06-cross-dialect.md`: final DDL, query
   differences, deviations from PostgreSQL, results table (baseline / v1 / v2), regressions.

Return a summary of ≤300 words with paths, test results and regressions.
