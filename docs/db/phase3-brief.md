# Phase 3 brief: cross-dialect validation

Shared brief for the Phase 3 agents. Postgres is the baseline, and Postgres results decided what was accepted. Phase 3 only checks
that the accepted changes are portable and don't regress on the other databases. It also finds the per-dialect equivalent where
Postgres syntax (partial indexes, INCLUDE, LATERAL, extended statistics) is unavailable.

## Accepted Postgres changes (set S1, see `experiments/H60-consolidated-set.md`, `experiments/H60/`)
Indexes (`experiments/H60/indexes-S1.sql`):
1. Add the partial covering `idx_outbox_record_incomplete_created (created_at, id) INCLUDE (partition_no, status, next_retry_at, record_key) WHERE completed_at IS NULL`.
2. Drop `idx_outbox_record_record_key_created` (prefix duplicate of `record_key_completed_created`).
3. Add extended statistics `(mcv) ON status, completed_at`. This fixes the misestimate that comes from treating `status = 'NEW'` and `completed_at IS NULL` as independent.
4. Optional (S2): replace `record_key_completed_created` with `(record_key, completed_at, created_at, id)`.
Keep `status_retry`, `status`, `partition_status_retry`.

Query rewrites (`experiments/H60/queries/`, Postgres syntax):
- Both key queries (`findRecordKeysInPartitions` with and without the filter) and `findOldestDueNextRetryAtByPartitions` (with the filter) use the head-of-key form. The outer side scans incomplete rows in `(created_at, id)` order and stops early on a real `LIMIT :batch`. A correlated per-key probe checks that the row is the first incomplete record of its key. Keep `withMaxRows` as well.
- `findIncompleteRecordPages` uses the "Lon" form: the key list is joined `CROSS JOIN LATERAL` to an ordered per-key probe (`completed_at IS NULL AND status = 'NEW' ORDER BY completed_at, created_at, id`), with no top ORDER BY. The consumer needs only per-key `(created_at, id)` order. Dialects without LATERAL keep the IN form.
- `findOldestCreatedAtByPartitions` adds `AND completed_at IS NULL`.
- The cleanup selects (`find{Completed,Failed}RecordsBefore`, `delete{Completed,Failed}Records_selectIds`) use a real SQL limit.
Equivalence relies on the invariant NEW/FAILED ⇔ `completed_at IS NULL` (verified in core, see `experiments/H09-*.md`).

User idea, measured on Postgres (H09): define "incomplete" in the queries by status (`status IN ('NEW','FAILED')` or `status <> 'COMPLETED'`) instead of `completed_at IS NULL`.
- With a non-partial outer index `(status, created_at, id)` plus the covered columns, the key query takes 0.1–0.4 ms, works with generic plans, and is portable.
- The cost is about 130 MB and +9%/+12% WAL on Postgres.
- This is the main candidate for databases without partial indexes. Compare it with a `(completed_at, created_at, id)` non-partial form (H07).

## Per-dialect questions
- Which outer index works: filtered/partial, non-partial `(completed_at, created_at, id, ...)`, or status-leading `(status, created_at, id, ...)` with the status-predicate query? Oracle also has the trick that all-NULL keys are not indexed, i.e. a function-based index such as `CASE WHEN completed_at IS NULL THEN ... END`.
- Limit syntax: `LIMIT`, `FETCH FIRST n ROWS ONLY`, `OFFSET 0 ROWS FETCH NEXT n ROWS ONLY`, `TOP`. Does the planner stop early?
- LATERAL / CROSS APPLY support for the pages query, or keep the IN form.
- Statistics: the equivalent of extended statistics (MySQL histograms, SQL Server multi-column stats, Oracle column groups), if a misestimate shows up.
- Syntax issues: partial indexes, `NULLS`, INCLUDE, IN-list limits (Oracle 1000, SQL Server 2100 parameters).
- Does dropping `record_key_created` hurt anything?

## Method
- Container images: `mysql:9.2`, `mariadb:12`, `mcr.microsoft.com/mssql/server:2022-latest`, `gvenzl/oracle-xe:18-slim-faststart`, `mongo` (version from the Mongo tests).
- One container at a time, named `outbox-db-P3-<db>`, removed when done (`docker rm -f -v`). Check `df -h` first; the host disk is small, so use tmpfs for data where practical.
- Schema from `namastack-outbox-jdbc/src/main/resources/schema/<db>/outbox-tables.sql`.
- Scenarios A and B only (`harness/seed-A.sql` and `harness/seed-B.sql` show the data shape), at 200k rows if 1M is too slow (record the scale). Keep the same proportions: 0.6% NEW, and a 20% NEW backlog.
- Queries come from `namastack-outbox-jdbc/src/main/kotlin/io/namastack/outbox/JdbcOutboxRecordRepository.kt` (the current form) and the rewritten forms above, translated to the dialect.
- Plans: `EXPLAIN ANALYZE` / `EXPLAIN FORMAT=TREE` (MySQL/MariaDB `ANALYZE FORMAT=JSON`), `SET STATISTICS PROFILE ON` / `STATISTICS XML` (SQL Server), `DBMS_XPLAN.DISPLAY_CURSOR` with `gather_plan_statistics` (Oracle).
- Report per query: before → after plan shape, index used, time (median of 3 warm runs), equivalence (same rows, same order), and write cost if cheap to measure.
- Write scripts and raw output under `docs/db/phase3/<db>/` (keep it small). Write the section to `docs/db/90-cross-dialect/<db>.md`.
- Do not modify `src/` or schema files.
