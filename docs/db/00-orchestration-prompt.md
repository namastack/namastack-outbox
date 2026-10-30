# Task: Orchestrated outbox_record index and query investigation

You are the **orchestrator**. Coordinate subagents through repeated loops of
hypothesis → experiment → regression check → evaluation. The goal is a complete,
evidence-backed picture of which indexes on `outbox_record` should be added, removed or
modified, and which queries should be rewritten to use existing or new indexes.
The deliverable is a final report. **This is an investigation: do not change
production source code or schema files.** All experiments run in throwaway Docker containers.
All results go under `docs/db/` in this repository. `docs/db/` is a temporary workspace for this
experiment only: store scripts, raw output, notes and anything else there freely.

**PostgreSQL is the baseline.** Most users run Postgres, so all measurements, the acceptance
decisions and the final ranking are based on Postgres 18. The other databases (MySQL, MariaDB,
SQL Server, Oracle, plus JPA and MongoDB) are checked only in Phase 3, to confirm that the accepted
changes are portable and don't regress there. H2 is out of scope.

Repository: `/home/zamo/git/namastack-outbox` (read `CLAUDE.md` first).
Branch: `feature/539-bulk-record-loading`.

## 1. Background (already measured)

Postgres 18 (`postgres:18-alpine`), 1M rows, 50k keys × 20 records each, completed records retained
(the default `delete-completed-records=false`), 64 of 256 partitions owned.
Scenario A: 0.6% `NEW`. Scenario B: backlog with 20% `NEW`.

| Query | A | B | Problem |
|---|---|---|---|
| `findRecordKeysInPartitions` (previous-failure filter on) | status idx + NL anti join, 7.9 ms | **parallel seq scan + hash anti join, 211 ms** | runs on every poll |
| `findRecordKeysInPartitions` (filter off) | status idx, partition as filter, 1.6 ms | 97 ms | `partition_status_retry` not used |
| `findOldestCreatedAtByPartitions` | status idx, partition as filter, 1.7 ms | 75 ms | metrics, periodic |
| `findOldestDueNextRetryAtByPartitions` (filter on) | NL anti join, 5.5 ms | not measured | same shape as key query |
| `findIncompleteRecordPages` (1000 keys) | status idx + key filter, 4.5 ms | BitmapAnd, 26 ms | acceptable |
| `findCompletedRecords(cutoff, limit)` / `deleteCompletedRecords` | seq scan + full sort, 300–1000 ms | 94 ms (10% cutoff) | no `(status, completed_at)` index |
| `countByStatus(COMPLETED)` | parallel index-only, 33 ms | not measured | grows with history; used by metrics and `OutboxInstanceRegistry` |
| `deleteByStatus(COMPLETED)` | seq scan, 9.5 s | not measured | admin |

Index usage observed:
- `idx_outbox_record_partition_status_retry` is used only by the count queries.
- `idx_outbox_record_status_retry` is used only by `findOldestDueNextRetryAt` without the filter.
- `idx_outbox_record_record_key_created` and `idx_outbox_record_record_key_completed_created` are used interchangeably.
- `.withMaxRows(limit)` is applied by the JDBC driver, not as SQL `LIMIT`. Assume the planner never sees the limit, but verify this (see 5.4).

## 2. Scope

**Sources of truth** (read them; never copy queries from this prompt when the code differs):
- JDBC queries: `namastack-outbox-jdbc/src/main/kotlin/io/namastack/outbox/JdbcOutboxRecordRepository.kt`
- DDL for all dialects: `namastack-outbox-jdbc/src/main/resources/schema/{postgres,mysql,mariadb,sqlserver,oracle}/outbox-tables.sql`
- JPA: `namastack-outbox-jpa/.../OutboxRecordEntity.kt` (`@Table(indexes=...)`), `JpaOutboxRecordRepository.kt`
- MongoDB: `namastack-outbox-mongodb/.../MongoOutboxRecordEntity.kt` (`@CompoundIndexes`), `MongoOutboxRecordRepository.kt`
- SPI contract and semantics: `namastack-outbox-core/src/main/kotlin/io/namastack/outbox/OutboxRecordRepository.kt`
- Callers: grep for each method to classify it as hot path, periodic or admin.

**Query priority:**
1. **Hot path, every poll:** `findRecordKeysInPartitions` (both variants), `findIncompleteRecordPages`, `updateProcessingState`, `insert` (`create`).
2. **Periodic, metrics or heartbeat:** `countByStatus`, `countRecordsByPartition(s)`, `findOldestCreatedAtByPartitions`, `findOldestDueNextRetryAtByPartitions` (both variants).
3. **Cleanup:** `findCompletedRecords(cutoff, limit)`, `findFailedRecords(cutoff, limit)`, `deleteCompletedRecords`, `deleteFailedRecords`.
4. **Admin, rare:** `findPendingRecords`, `findCompletedRecords()`, `findFailedRecords()`, `deleteByStatus`, `deleteByRecordKeyAndStatus`, `deleteById(s)`, `update`.

**Semantics that every rewrite must preserve** (verify against the code and KDoc):
- `completed_at IS NULL` means `NEW` or `FAILED`. `FAILED` keeps `completed_at` NULL and blocks later records of its key when `ignoreRecordKeysWithPreviousFailure=true`.
- Per-key order is `(created_at, id)`. Keys are returned ordered by `MIN(created_at)` of their due `NEW` records.
- Records with `next_retry_at > now` are not due, but still block later records of the same key.
- Results must be identical (same rows, same order) to the current query in every scenario.

## 3. Deliverables in `docs/db/`

```
docs/db/
  README.md                  # what this is, how to rerun the harness, index of files
  STATE.md                   # orchestrator ledger (see section 4), always kept up to date
  harness/
    start-postgres.sh        # starts container `outbox-db-<name>`, applies schema from the repo DDL
    seed-<scenario>.sql      # one file per scenario (section 5.2)
    queries/<method>.sql     # every JDBC query with literal parameters; IN lists generated with \gset
    run-explain.sh           # runs all or selected queries against a scenario, writes output to results/
    compare-results.sh       # semantic equivalence check: original vs rewritten query (5.5)
  01-baseline.md             # baseline plans and timings, all queries × all scenarios
  queries/<method>.md        # per-query dossier: callers, baseline, hypotheses tried, outcome
  experiments/H<nn>-<slug>.md  # one file per hypothesis (template in 4.3)
  results/                   # raw EXPLAIN output (gitignore-worthy; keep it small, trim IN lists)
  90-cross-dialect.md        # validation of accepted changes on the other databases
  99-final-report.md         # the deliverable (section 6)
```

## 4. Orchestration

### 4.1 Rules for the orchestrator
- Delegate the work to subagents. Keep only summaries in your own context; the details live in files.
- **Every subagent prompt is self-contained.** Subagents start cold, so give each one: the repo path, the
  files to read, the hypothesis or query ID, the scenario list, harness paths, the output file to write,
  and the expected return format: at most 15 lines, with the file paths written.
- **Benchmarks run sequentially** (one timing agent at a time) because parallel containers distort timings.
  Analysis and hypothesis agents may run in parallel.
- Each experimenting agent uses its own container name (`outbox-db-H07`) and removes it when done.
- Update `STATE.md` after every agent returns, so the work can resume after context compaction.
  Before starting any step, reread `STATE.md`.
- Do not edit `src/` or the schema files. If a code change is the recommendation, describe it as a diff
  in the experiment file.

### 4.2 Phases

**Phase 0: harness and baseline** (one agent, `general-purpose`)
Build `docs/db/harness/` (section 5) and produce `01-baseline.md`. Cover every JDBC repository method
in every scenario, with the plan shape, the indexes used, execution time (median of 3 warm runs), shared
buffers hit/read, sorts/spills and rows removed by filter. Also capture `pg_stat_user_indexes` after a
representative workload mix (5.6) to see which indexes are actually used. Also record table and index
sizes (`pg_relation_size`).

**Phase 1: per-query loops** (in priority order from section 2; run one loop per query or per group
of related queries, e.g. the two `findRecordKeysInPartitions` variants together with
`findOldestDueNextRetryAtByPartitions` because they share the anti-join shape)

```
loop (max 4 iterations per query group):
  1. ANALYST (general-purpose): reads the code, baseline, dossier and earlier experiments, and
     proposes 1–4 hypotheses. Each hypothesis is an index change (add/modify/drop), a query rewrite, or both.
     Each one needs the expected plan, why the planner should choose it, a semantic-equivalence argument
     and a portability note (Postgres first; MySQL, MariaDB, SQL Server, Oracle, JPA, Mongo).
     Writes experiments/H<nn>-*.md (status: proposed).
  2. EXPERIMENTER (general-purpose, sequential): for each hypothesis, applies it in a fresh
     container per scenario, proves result equivalence (5.5), runs EXPLAIN ANALYZE for the target
     query (3 warm runs, median), and also tests the generic plan (5.4).
     Appends the results to the H file (status: measured | invalid).
  3. REGRESSION (general-purpose, sequential, only if the hypothesis adds/changes/drops an index):
     reruns the full query suite with the change applied to detect plan flips in other queries,
     measures write cost (5.7) and index size. Appends to the H file.
  4. EVALUATOR (orchestrator itself, or a general-purpose agent for complex cases): decides
     accept / reject / refine and records the reasoning in the H file and in STATE.md.
     Refine → next iteration with the feedback.
stop the loop when: an accepted hypothesis meets the target (section 6.1), or 4 iterations are
done, or two consecutive iterations improve by less than 15%.
```

**Phase 2: index set consolidation** (one analyst + one experimenter)
Combine all accepted index changes into one candidate index set. Also evaluate **removals**: an index
not used by any query after the accepted changes is a removal candidate. Rerun the full suite and the
write benchmark with the combined set on every scenario. The combined set must not regress any
hot-path query by more than 10%.

**Phase 3: cross-dialect validation** (one agent per database, **sequential**)
For the final candidate set and rewritten queries, check on the test images:
`mysql:9.2`, `mariadb:12`, `mcr.microsoft.com/mssql/server:2022-latest`, `gvenzl/oracle-xe:18-slim-faststart`
Use scenarios A and B only, at 200k rows if 1M seeding is too
slow, and record the scale. Report the plan per query (`EXPLAIN ANALYZE` / `EXPLAIN FORMAT=TREE`,
`SET STATISTICS PROFILE ON`, `DBMS_XPLAN.DISPLAY_CURSOR`), plus syntax issues: partial indexes, `NULLS`, `IN`
list limits. Also check that the JPA queries in `JpaOutboxRecordRepository` (Hibernate-generated SQL;
enable SQL logging in a test or derive it) and the Mongo queries benefit from or need equivalent changes.
For Mongo, use `explain("executionStats")` on `mongo` (use the version from the Mongo tests).
Write `90-cross-dialect.md`.

**Phase 4: final report** (orchestrator writes `99-final-report.md`, section 6).

### 4.3 Hypothesis file template (`experiments/H<nn>-<slug>.md`)
```
# H<nn>: <title>
Status: proposed | measured | accepted | rejected | refined→H<mm>
Target queries: ...
Change: <DDL and/or rewritten SQL, Postgres syntax>
Rationale / expected plan: ...
Semantic equivalence argument: ...
Portability: postgres / mysql / mariadb / sqlserver / oracle / jpa entity / mongo
## Results (per scenario): plan shape, indexes used, median ms, buffers, before → after
## Equivalence check: PASS/FAIL + method
## Regression: other queries whose plan changed; write cost; index size
## Decision + reasoning
```

## 5. Harness specification

### 5.1 Database
`docker run -d --rm --name outbox-db-<x> -e POSTGRES_PASSWORD=pw postgres:18-alpine`. Apply the
schema directly from `namastack-outbox-jdbc/src/main/resources/schema/postgres/outbox-tables.sql`.
Run psql via `docker exec -i`. Seeding must be deterministic (no `random()` without `setseed`).
Run `VACUUM ANALYZE` after seeding. Use `EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING OFF, SUMMARY ON)`
for plan shape, plus a separate run with COSTS on when estimate errors matter. Trim long IN lists from
saved output.

### 5.2 Scenarios (1M rows unless stated; partition = `abs(hashtext(record_key)) % 256`)
- **A, steady state:** 50k keys × 20, history retained; keys 1..2000 have their last 3 records `NEW`;
  keys 1..200 have a `FAILED` record before the `NEW` ones; keys 1..100 have `NEW` with `next_retry_at` 1h in the future.
- **B, backlog:** like A, but keys 1..25000 have their last 8 records `NEW` (200k `NEW`).
- **C, delete-completed mode:** only incomplete rows remain (≈ A without `COMPLETED` rows, then scaled to 200k `NEW` over 50k keys).
- **D, hot keys:** 1k keys × 1000 records (long aggregate histories), 2% `NEW` at the tail of 10% of the keys.
- **E, blocked keys:** like A, but 20% of the keys with `NEW` rows have a `FAILED` head (tests the anti join under heavy blocking).
Partition sets to test: 64 (4 instances) and 256 (single instance).
Key sets for `findIncompleteRecordPages`: 10, 100 and 1000 keys.

Reference seed for scenario A (adapt it for the others):
```sql
INSERT INTO outbox_record
SELECT md5(k::text || '-' || n::text),
  CASE WHEN k <= 2000 AND n >= 18 THEN 'NEW' WHEN k <= 200 AND n = 17 THEN 'FAILED' ELSE 'COMPLETED' END,
  'order-' || k, 'com.example.OrderEvent', repeat('x', 500), '{}', ts,
  CASE WHEN (k <= 2000 AND n >= 18) OR (k <= 200 AND n = 17) THEN NULL ELSE ts + interval '1 second' END,
  CASE WHEN k <= 200 AND n = 17 THEN 5 ELSE 0 END, NULL,
  CASE WHEN k <= 100 AND n >= 18 THEN now() + interval '1 hour' ELSE ts END,
  abs(hashtext('order-' || k)) % 256, 'com.example.Handler#handle'
FROM generate_series(1, 50000) k, generate_series(1, 20) n,
  LATERAL (SELECT now() - interval '30 days' + (n * 50000 + k) * interval '50 milliseconds' AS ts) t;
```
Note: in this seed all completed timestamps fall within ~14h, so cleanup cutoffs must be
chosen relative to the data (e.g. `percentile_disc(0.1)`), not to `now()`.

### 5.3 Measurement
Use 1 warm-up run plus 3 measured runs and report the median. Report buffers next to time, because
buffers are the more stable signal. Record whether a sort spilled to disk.

### 5.4 Prepared statements and limits
The application runs these through pgjdbc prepared statements. Check the hot-path queries with
`PREPARE` + `SET plan_cache_mode = force_generic_plan` and compare with the custom plan,
because IN-list sizes and parameter values vary. Confirm or refute that `.withMaxRows` is invisible to
the planner (read the pgjdbc behaviour or test it with `auto_explain` + a small JDBC run). If it is
invisible, evaluate rewriting those queries with a real SQL limit and note the portability (`LIMIT`,
`FETCH FIRST`, `TOP`).

### 5.5 Semantic equivalence
For every rewrite, run the original and the rewritten query on all scenarios and compare the ordered
output exactly (e.g. `md5(string_agg(row::text, '|' ORDER BY ordinality))` over `WITH ORDINALITY`, or
diff the psql output). For partition/key queries, also test with mixed `next_retry_at`, FAILED heads,
equal `created_at` ties (insert tie rows deliberately) and empty partitions.

### 5.6 Index usage
Run `SELECT pg_stat_reset();`, then a workload mix approximating production: 100 polls (key query +
bulk load + 1 updateProcessingState per record), 10 metrics scrapes and 1 cleanup. Then read
`pg_stat_user_indexes` (`idx_scan`, `idx_tup_read`).

### 5.7 Write cost
Measure the insert and update cost per index set with a pgbench custom script (`pgbench -n -f` inside
the container) or a timed batch of 50k `INSERT` + 50k `updateProcessingState` updates. Report tps or
ms per 1k operations, plus the total index size.

## 6. Final report (`docs/db/99-final-report.md`)

### 6.1 Targets used for acceptance
- Hot-path queries must not seq scan, and their cost should scale with the incomplete rows in owned
  partitions, not with the table size (check this in A vs B vs D).
- Hot-path latency in scenario B should drop by ≥ 50%, or the reasoning for why not is documented.
- Write cost increase ≤ 15% for the full recommended index set.

### 6.2 Contents
1. **Summary** (≤ 10 lines): what to change and the expected gain.
2. **Recommendation table:** one row per change.
   ```
   | # | Type (add/drop/modify index, rewrite query) | Change | Queries affected | Before → after (A/B/D) | Write cost | Portability | Risk | Evidence (H file) |
   ```
3. **Index set:** current vs proposed for Postgres first, then per other dialect (incl. JPA `@Table(indexes)` and Mongo `@CompoundIndexes`).
4. **Query rewrites:** before/after SQL and the semantic-equivalence evidence.
5. **Rejected ideas:** one line each, with the reason. This is important, so nobody retries them.
6. **Per-query matrix:** every repository method → index used before/after, with timings.
7. **Migration notes:** schema change across all dialects + Mongo, effect on existing installs
   (`CREATE INDEX` on large tables, `CONCURRENTLY` where available), an ADR is required
   (`docs/adr/`, `GOVERNANCE.md`), and release notes.
8. **Open questions / follow-ups.**

Keep the report factual and short. Every number must link to an H file or to `01-baseline.md`. Headline numbers are Postgres;
other databases are reported in their own section.

## 7. Start
1. Read `CLAUDE.md`, the sources in section 2, and this prompt.
2. Create `docs/db/STATE.md` with the phase plan and an empty hypothesis ledger.
3. Launch Phase 0.
