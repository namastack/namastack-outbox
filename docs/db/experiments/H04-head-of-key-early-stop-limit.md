# H04: "Head of key" rewrite with a real SQL LIMIT (early-stop ordered scan)
Status: measured
Target queries: `findRecordKeysInPartitions` (filter on and off), `findOldestDueNextRetryAtByPartitions` (filter on)
Builds on: H01 (bound) + H02 (probe index). Independent of H03.

## Change

DDL (`experiments/H04/indexes.sql`):

```sql
CREATE INDEX idx_outbox_record_incomplete_key
    ON outbox_record (record_key, created_at, id)
    WHERE completed_at IS NULL;                                   -- H02
CREATE INDEX idx_outbox_record_incomplete_created
    ON outbox_record (created_at, id)
    INCLUDE (partition_no, status, next_retry_at, record_key)
    WHERE completed_at IS NULL;
```

SQL, key query, filter on (`batchSize` becomes a bind parameter; `withMaxRows` can stay):

```sql
SELECT o.record_key
FROM outbox_record o
WHERE o.partition_no IN (:partitions)
  AND o.status = :status
  AND o.next_retry_at <= :now
  AND o.completed_at IS NULL
  AND NOT EXISTS (
    SELECT 1 FROM outbox_record older
    WHERE older.record_key = o.record_key
      AND older.completed_at IS NULL
      AND older.created_at <= o.created_at
      AND (
        older.created_at < o.created_at
        OR (older.created_at = o.created_at AND older.id < o.id)
      )
  )
ORDER BY o.created_at ASC, o.id ASC
LIMIT :batchSize
```

Key query, filter off ("first due record of each key"):

```sql
SELECT o.record_key
FROM outbox_record o
WHERE o.partition_no IN (:partitions)
  AND o.status = :status
  AND o.next_retry_at <= :now
  AND o.completed_at IS NULL
  AND NOT EXISTS (
    SELECT 1 FROM outbox_record earlier
    WHERE earlier.record_key = o.record_key
      AND earlier.completed_at IS NULL
      AND earlier.status = :status
      AND earlier.next_retry_at <= :now
      AND earlier.created_at <= o.created_at
      AND (
        earlier.created_at < o.created_at
        OR (earlier.created_at = o.created_at AND earlier.id < o.id)
      )
  )
ORDER BY o.created_at ASC, o.id ASC
LIMIT :batchSize
```

MIN query, filter on (scalar subquery keeps "exactly one row, NULL when empty"):

```sql
SELECT (
  SELECT o.next_retry_at
  FROM outbox_record o
  WHERE o.partition_no IN (:partitions)
    AND o.status = :status
    AND o.next_retry_at <= :now
    AND NOT EXISTS ( <H01 predecessor subquery> )
  ORDER BY o.next_retry_at ASC
  LIMIT 1
)
```

Harness files: `experiments/H04/queries/{findRecordKeysInPartitions_filter,findRecordKeysInPartitions_nofilter,findOldestDueNextRetryAtByPartitions_filter}.sql`.
New psql variables: `batch` (LIMIT, default 10 = default `batchSize`), `partlist_override` (custom mode only, e.g.
`999` = owned partitions without any record, the worst case for an early-stop scan).

Kotlin (sketch, JDBC): the repository needs a dialect-specific limit clause, chosen once (e.g. from
`DatabaseMetaData.getDatabaseProductName()` in `JdbcOutboxPersistenceFactory`, or a configurable strategy):

```diff
-        GROUP BY o.record_key
-        ORDER BY MIN(o.created_at) ASC
+        ORDER BY o.created_at ASC, o.id ASC
+        ${limitClause(":batchSize")}
 ...
         return jdbcClient
             .sql(query)
             .withMaxRows(batchSize)
+            .param("batchSize", batchSize)
```

## Rationale / expected plan

The application needs `batchSize` (default 10) keys, but the current SQL must produce *all* qualifying keys first
(GROUP BY + ORDER BY MIN, limit invisible to the planner, see `01-baseline.md`): B returns 6,115 keys at 64
partitions to keep 10. Two observations make an early stop possible:

1. With the filter on, a qualifying row is the first incomplete record of its key (no incomplete predecessor), so at
   most one row per key qualifies (given `o.completed_at IS NULL`): `GROUP BY` is a no-op and `MIN(o.created_at)` is
   that row's `created_at`. Without the filter, "first due NEW record of the key" gives the same one-row-per-key form.
2. Rows can then be produced in `(created_at, id)` order straight from an index and the scan can stop after
   `batchSize` hits.

Expected plan (key query): `Limit -> Nested Loop Anti Join -> Index Only Scan using idx_outbox_record_incomplete_created`
(Filter: `partition_no = ANY(...) AND status = 'NEW' AND next_retry_at <= now`, evaluated on the index tuple) with an
inner `Index Only Scan using idx_outbox_record_incomplete_key` (`record_key = o.record_key AND created_at <= o.created_at`).
No Sort, no HashAggregate, no Gather. The planner picks it because a `Limit` over an ordered path is costed by its
startup + fraction of run cost; this only works with a real `LIMIT` (hence the SQL change).

Rows scanned before 10 hits: roughly `10 / (owned fraction x fraction of incomplete rows that are due heads)`.
Seeds: B ~40-60 index entries (most oldest incomplete rows are heads), A/E a few hundred (FAILED heads and blocked /
not-due keys come first in time), D a few hundred. Expected serial: **A/B/C/D/E < 1 ms at 64 and 256 partitions**
(B from 265 / 367 ms). Worst case (owned partitions contain no due head, `partlist_override=999`): full scan of the
partial index, B 200k entries, ~15-30 ms index-only (heap fetches if not all-visible) — still below the baseline.

MIN query: `Result -> InitPlan -> Limit -> Nested Loop Anti Join -> Index Scan using idx_outbox_record_status_retry`
(`status = 'NEW' AND next_retry_at <= now`, ascending; `partition_no` as filter) with the H02 probe. The first owned,
unblocked due row ends the scan: expected < 1 ms in all scenarios (from B 165 / 214 ms, D 39 / 139 ms). This gives
`status_retry` a real use; Phase 2 must not drop it if H04 is accepted (or replace it by a partial
`(next_retry_at) INCLUDE (...) WHERE completed_at IS NULL`).

Risk: the planner's anti-join selectivity estimate. If it believes few rows survive the anti join it may prefer a
full plan + top-N sort; check `costs` output. Generic plans: `LIMIT $n` is estimated at 10% of rows, still favouring
the ordered path; must be measured.

Alternatives considered and not proposed:
- `DISTINCT ON (record_key) ... ORDER BY record_key, created_at, id` / `ROW_NUMBER() OVER (PARTITION BY record_key ...)`
  over incomplete rows ("first incomplete record per key is NEW and due"): always reads every incomplete row in the owned
  partitions plus a sort, no early stop; `DISTINCT ON` is Postgres-only; window form costs about what H03 costs.
- Real `LIMIT` on the current GROUP BY query: no gain, the aggregate and sort still consume all input (baseline 5.4).

## Semantic equivalence argument

Key query, filter on: original row set R = due `status` rows in partitions with no incomplete predecessor in
`(created_at, id)` order. Rewrite adds `o.completed_at IS NULL`, identical for `status` in {NEW, FAILED} under the
lifecycle invariant (see H03). If two rows r1 < r2 of one key were both in R, r1 (incomplete) would be a predecessor of
r2: contradiction, so R has at most one row per key, `GROUP BY record_key` returns the same keys and
`MIN(o.created_at) = o.created_at`. Ordering by `(o.created_at, o.id)` is the original order, with ties between keys
on `created_at` broken by `id` (the original leaves that tie order to the plan; seeds A-E have unique `created_at`;
`seed-edge` with `key_ties=1` may differ only in tie order, which the contract does not specify). Because
`(created_at, id)` is a total order, `LIMIT n` returns exactly the first n rows of the full ordered result = what
`withMaxRows(n)` returns today. Records with `next_retry_at > now` remain incomplete, so they still block later
records in the probe; FAILED heads block likewise.

Key query, filter off: for each key with at least one due `status` row, exactly one row o survives (the minimum in
`(created_at, id)` among its due rows), and `o.created_at = MIN(created_at)` of those rows. Same order/tie/LIMIT
argument.

MIN query: `MIN(x)` over set S equals the first `x` of S in ascending order; `next_retry_at` is `NOT NULL`; the
scalar subquery yields NULL for empty S, so the output is exactly one row in both forms
(`.list().firstOrNull()` in the repository handles it identically).

## Portability

- Limit syntax (bind parameter allowed everywhere):
  - postgres, mysql, mariadb, h2: `LIMIT ?`;
  - postgres, oracle 12c+, mariadb 10.6+, h2: `FETCH FIRST ? ROWS ONLY`;
  - sqlserver: `OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY` (requires `ORDER BY`, present) or `SELECT TOP (?)`;
  - MySQL has no `FETCH FIRST`, SQL Server no `LIMIT`: a per-dialect clause is unavoidable in the JDBC module.
  - Scalar subquery with limit: valid in postgres/mysql/mariadb (`(SELECT ... LIMIT 1)`), sqlserver
    (`(SELECT TOP 1 ...)`), oracle (`(SELECT ... FETCH FIRST 1 ROWS ONLY)` + `FROM dual`).
- Indexes: sqlserver filtered indexes with `INCLUDE` as written. mysql/mariadb/oracle: no partial indexes; use
  non-partial `(created_at, id)` (InnoDB: `(created_at)` already carries the PK) — but a non-partial ordered index
  scans completed history first (oldest rows are COMPLETED), so the early stop is **lost** on those databases unless
  `completed_at` leads: `(completed_at, created_at, id)` with `completed_at IS NULL` as equality-like access (MySQL
  `ref`/range on NULL, Oracle IS NULL on a composite with non-null trailing columns). Test this in Phase 3.
- jpa: Hibernate already renders `setMaxResults` as a real limit per dialect, so the JPQL change is only the query
  text (`order by o.createdAt, o.id`, no `group by`). `@Table(indexes)` cannot express the partial indexes (use the
  `(completedAt, createdAt, id)` composite as the portable entity index).
- mongo: separate aggregation; the equivalent is a `$sort {createdAt, _id}` + `$limit` early in the pipeline on a
  `{completedAt, createdAt}` index; Phase 3.

## Experiment plan

1. Scenarios A, B, C, D, E; parts 64 and 256.
   `./run-suite.sh H04-<S> <S> --ddl ../experiments/H04/indexes.sql -q ../experiments/H04/queries --skip-writes --keep`
   (`batch` defaults to 10).
2. Also `batch=100` and `batch=1000` on B and D (`run-explain.sh ... --tag serial-b1000 --set max_parallel_workers=0`
   with `-v batch=1000` or `findRecordKeysInPartitions_filter@parts=64,batch=1000`), to see how cost grows with the
   batch size.
3. Worst case: `findRecordKeysInPartitions_filter@parts=64,partlist_override=999`, same for nofilter and MIN, on B,
   C and E (serial). Compare with baseline serial of the same query and with H03.
4. Generic plan: `generic` and `generic-serial` (LIMIT is `$n`); report the chosen shape. If the generic plan falls
   back to sort + full scan, note it (custom plans are what pgjdbc gets today, see baseline probe).
5. Equivalence: `./compare-results.sh outbox-db-H04-<S> queries/findRecordKeysInPartitions_filter.sql ../experiments/H04/queries/findRecordKeysInPartitions_filter.sql --var parts=64 --var batch=100000000`
   and parts=256; same for `_nofilter` and the MIN file (no `batch`). Edge seed with and without `key_ties=1`
   (expected: PASS, or "same rows, different order" only within cross-key `created_at` ties for `key_ties=1`;
   inspect the diff to confirm). Additionally with `--var batch=10` diff against `head -10` of the original output.
6. Regression (two new indexes): full suite with writes `./run-suite.sh H04-reg-<S> <S> --ddl ../experiments/H04/indexes.sql`
   for A, B, D. Watch `findOldestCreatedAtByPartitions` (could the planner use `incomplete_created` for MIN(created_at)?
   only if the query had `completed_at IS NULL`), cleanup selects, `findPendingRecords`; write cost and sizes
   (incl. after `workload-mix.sh`).

## Results (per scenario): plan shape, indexes used, median ms, buffers, before → after
Method (G1 iteration 1, shared by H01–H04): one container per scenario (`outbox-db-H01`, 1M rows, C 200k), index
states applied **in place after seeding** (`CREATE INDEX` + `VACUUM ANALYZE`): control (repo indexes, original SQL,
`results/G1ctl-<S>/`) → H01 SQL → +H02 index → +H03 index → H04 (H03 partition index dropped, H04 created index
added) → H03b (B, D). Driver: `experiments/G1-iter1/run-scenario.sh`; query dirs = `harness/queries` overlaid with
the H files (`make-qdirs.sh`). Per state: target queries in `custom-serial` (pgjdbc `withMaxRows` execution, the
app's real mode for the key queries), `generic-serial`, `custom` (with workers; the MIN query has no `withMaxRows`,
so this is its real mode) and `generic`; 1 warm-up + 3 runs, median; buffers = shared hits. "ctl" numbers come from
the same container before any change (they match `results/base-<S>` within noise). Tables: ms (shared hits),
ctl → hypothesis; "keys on/off" = `findRecordKeysInPartitions` filter on/off, "MIN on/off" =
`findOldestDueNextRetryAtByPartitions` filter on/off. Raw: `results/<H>-<S>/`. All data pages all-visible (fresh
VACUUM; Heap Fetches ≈ 0): the visibility-map sensitivity run (H02 step 5) was not done.

H04 (`incomplete_key` + `incomplete_created`, H04 SQL, `batch=10`):

**custom-serial** — ms (shared hits), ctl → H04

| Query | parts | A | B | C | D | E |
|---|---|---|---|---|---|---|
| keys on | 64 | 5.27 (7,374) → 0.166 (95) | 266 (106,225) → 0.2 (139) | 132 (33,368) → 0.179 (139) | 38.1 (7,273) → 0.085 (37) | 5.71 (7,287) → 0.274 (186) |
| keys on | 256 | 20.3 (27,806) → 0.264 (229) | 428 (106,225) → 0.357 (340) | 208 (33,368) → 0.294 (340) | 139 (7,273) → 0.092 (49) | 19.9 (26,509) → 0.614 (634) |
| keys off | 64 | 1.43 (510) → 0.115 (47) | 55.6 (16,832) → 0.109 (58) | 29.5 (16,434) → 0.115 (58) | 4.66 (1,858) → 14.1 (256) | 1.49 (509) → 0.123 (49) |
| keys off | 256 | 2.83 (510) → 0.122 (46) | 73.8 (16,832) → 0.128 (57) | 75.4 (16,684) → 0.123 (57) | 5.88 (1,858) → 13.6 (256) | 2.42 (509) → 0.134 (49) |
| MIN on | 64 | 4.89 (7,374) → 0.133 (82) | 267 (106,225) → 0.167 (115) | 124 (33,368) → 0.161 (116) | 37.1 (7,273) → 0.062 (12) | 5.24 (7,287) → 0.266 (185) |
| MIN on | 256 | 17.6 (27,806) → 0.278 (216) | 364 (106,225) → 0.322 (316) | 178 (33,368) → 0.269 (317) | 139 (7,273) → 0.09 (24) | 18.3 (26,509) → 0.604 (633) |
| MIN off | 64 | 0.035 (4) → 0.032 (4) | 0.042 (4) → 0.04 (4) | 0.047 (4) → 0.035 (4) | 0.032 (4) → 0.034 (4) | 0.036 (4) → 0.035 (4) |
| MIN off | 256 | 0.039 (4) → 0.037 (4) | 0.044 (4) → 0.042 (4) | 0.036 (4) → 0.037 (4) | 0.033 (4) → 0.034 (4) | 0.04 (4) → 0.04 (4) |

**generic-serial** — ms (shared hits), ctl → H04

| Query | parts | A | B | C | D | E |
|---|---|---|---|---|---|---|
| keys on | 64 | 10.5 (7,374) → 0.294 (95) | 234 (106,009) → 0.59 (139) | 107 (33,118) → 0.592 (139) | 54.9 (7,273) → 0.145 (37) | 10.9 (7,287) → 0.561 (186) |
| keys on | 256 | 49.6 (9,678) → 0.588 (229) | 915 (106,225) → 1.08 (340) | 787 (34,088) → 1.07 (340) | 197 (7,273) → 0.172 (49) | 50.6 (9,693) → 1.51 (634) |
| keys off | 64 | 6.76 (510) → 0.208 (47) | 26.7 (16,616) → 0.462 (58) | 29.8 (16,434) → 0.438 (58) | 20.7 (1,858) → 0.428 (44) | 6.68 (509) → 0.165 (49) |
| keys off | 256 | 19.6 (510) → 0.199 (46) | 621 (16,832) → 0.624 (57) | 639 (17,404) → 0.612 (57) | 61.8 (1,858) → 0.532 (48) | 19.5 (509) → 0.228 (49) |
| MIN on | 64 | 10 (7,374) → 0.253 (82) | 228 (106,009) → 0.312 (115) | 88.9 (33,118) → 0.252 (116) | 55.2 (7,273) → 0.095 (12) | 11.7 (7,287) → 0.547 (185) |
| MIN on | 256 | 49.4 (9,678) → 0.594 (216) | 863 (106,225) → 0.624 (316) | 761 (34,088) → 0.612 (317) | 198 (7,273) → 0.135 (24) | 48.8 (9,693) → 1.57 (633) |
| MIN off | 64 | 0.065 (4) → 0.049 (4) | 0.046 (4) → 0.056 (4) | 0.042 (4) → 0.046 (4) | 0.041 (4) → 0.046 (4) | 0.043 (4) → 0.053 (4) |
| MIN off | 256 | 0.08 (4) → 0.082 (4) | 0.086 (4) → 0.088 (4) | 0.083 (4) → 0.082 (4) | 0.076 (4) → 0.1 (4) | 0.083 (4) → 0.089 (4) |

**custom** — ms (shared hits), ctl → H04

| Query | parts | A | B | C | D | E |
|---|---|---|---|---|---|---|
| keys on | 64 | 5.31 (7,374) → 0.16 (95) | 169 (106,367) → 0.197 (139) | 63.5 (33,368) → 0.187 (139) | 38 (7,273) → 0.089 (37) | 5.53 (7,287) → 0.273 (186) |
| keys on | 256 | 15.9 (27,864) → 0.28 (229) | 237 (106,372) → 0.322 (340) | 102 (33,368) → 0.299 (340) | 140 (7,273) → 0.095 (49) | 15.6 (26,567) → 0.608 (634) |
| keys off | 64 | 1.44 (510) → 0.121 (47) | 58.1 (16,832) → 0.117 (58) | 29.2 (16,434) → 0.118 (58) | 4.79 (1,858) → 14.6 (256) | 1.49 (509) → 0.122 (49) |
| keys off | 256 | 2.65 (510) → 0.125 (46) | 73.1 (16,832) → 0.128 (57) | 86.5 (16,684) → 0.129 (57) | 6.01 (1,858) → 14.1 (256) | 2.43 (509) → 0.145 (49) |
| MIN on | 64 | 4.88 (7,374) → 0.156 (82) | 162 (106,369) → 0.171 (115) | 62.8 (33,368) → 0.141 (116) | 37.9 (7,273) → 0.067 (12) | 5.18 (7,287) → 0.256 (185) |
| MIN on | 256 | 15.4 (27,848) → 0.245 (216) | 211 (106,370) → 0.327 (316) | 85.5 (33,368) → 0.282 (317) | 139 (7,273) → 0.099 (24) | 15.6 (26,551) → 0.573 (633) |
| MIN off | 64 | 0.036 (4) → 0.036 (4) | 0.041 (4) → 0.046 (4) | 0.035 (4) → 0.035 (4) | 0.035 (4) → 0.035 (4) | 0.037 (4) → 0.038 (4) |
| MIN off | 256 | 0.041 (4) → 0.039 (4) | 0.048 (4) → 0.043 (4) | 0.036 (4) → 0.037 (4) | 0.037 (4) → 0.034 (4) | 0.044 (4) → 0.041 (4) |

Plan shapes (custom-serial, batch 10): keys on = `Limit -> Nested Loop Anti Join -> Index Only Scan using
idx_outbox_record_incomplete_created` (partition/status/next_retry_at as index-tuple filter) with an inner
`Index Only Scan using idx_outbox_record_incomplete_key` (`record_key = o.record_key AND created_at <= o.created_at`),
no Sort / HashAggregate / Gather, as predicted; 37–634 buffers in all scenarios. MIN on = `Result -> Limit -> Nested
Loop Anti Join -> Index Scan using idx_outbox_record_status_retry` + `incomplete_key` probe. Keys off = same as keys
on with an `Index Scan` probe (needs `status`/`next_retry_at` from the heap) — except **D, see below**.

**Regression: keys off in D, custom plans**: 4.7 → 14.1 ms (64p), 5.9 → 13.6 ms (256p). The planner estimates the
inner side (`earlier`: due NEW rows) at 343 rows (actual 19,000), so it builds `Materialize` over a full scan of
`incomplete_created` and evaluates the join filter 190k times instead of probing `incomplete_key`. The generic plan
for the same query is fine (0.43 / 0.53 ms). Needs a refinement (e.g. drop `earlier.status`/`next_retry_at` from the
probe's sargable part or reshape the subquery so the `incomplete_key` probe is the only cheap path).

Batch size (B, custom-serial; `results/H04-B/*batch*`): keys on 64p/256p batch 10 → 100 → 1000:
0.20/0.36 → 0.45/0.49 → 2.6/2.4 ms (139 → 466 → 3.2k buffers); keys off 64p: 0.11 → 0.38 → 3.3 ms (batch 1000 picks a
parallel-capable Gather Merge, which pgjdbc would run serially; measured serially).

Worst case (`partlist_override=999`, owned partition without any record, custom-serial): B **15.3 ms** for keys on
and keys off (2,540 buffers = full scan of `incomplete_created`, 200k entries, no early stop), MIN on 0.04 ms;
control/H03 0.06 ms. In A/C/D/E the planner sees the partition is empty and uses another path (0.04–0.07 ms). So the
worst case is "full scan of the partial index" ≈ 15 ms per 200k incomplete rows when owned partitions hold no due head
(e.g. all due work is in partitions owned by other instances, or all heads blocked/not due) — still 17× below the
control's normal B cost, but it scales with *all* incomplete rows, not with owned ones.

Generic (`generic-serial`, `LIMIT $n`): ordered early-stop plan kept (`Limit -> Gather Merge -> Nested Loop Anti Join
-> Parallel Index Only Scan`), 0.15–1.6 ms everywhere (B keys on 915 → 1.1 ms at 256p); the generic plan is also the
healthy one for D keys off (0.43 ms).

## Equivalence check: PASS/FAIL + method
**PASS** (relies on the NEW ⇒ `completed_at IS NULL` invariant; tie order across keys with equal `MIN(created_at)` is
`(created_at, id)` in H04 and plan-dependent in the original).
- Scenarios A–E × parts 64/256: keys on, keys off (full output, `batch=100000000`), MIN on, and the small-batch check
  (rewritten with `batch=10` and `batch=100` vs `head -10/-100` of the original): exact PASS, 70/70 (A–E seeds have
  unique `created_at`).
- Edge seed without ties: exact PASS, 28/28 (custom + generic).
- Edge seed with `key_ties=1`: 21 of 28 checks are PASS-TIES (same rows; positions differ only inside groups of keys
  with equal `MIN(created_at)`, verified by identical sort-value sequences; this includes the batch-10/100 prefixes,
  where tied groups are not cut differently at the boundary), the 7 others exact PASS. No row-set difference anywhere.
  The original's own tie order changes between custom and generic plans (H01/H02 PASS-TIES), so this is not a
  behaviour change the contract can observe.
Method: `experiments/G1-iter1/eq-check.sh` (ordered output of original vs rewritten via psql; on mismatch a tie
analysis maps every returned key to its sort value `MIN(created_at)` of its due NEW rows: identical row multiset +
identical sort-value sequence = **PASS-TIES**, i.e. the only difference is the order among keys with equal
`MIN(created_at)`, which the original query leaves to the plan). Rewritten H04 key queries run with
`batch=100000000` for the full comparison.
- Scenarios A, B, C, D, E (index state of the hypothesis, custom plans), parts 64 and 256: `results/<H>-<S>/equivalence.txt`.
- Edge seed (`seed-edge.sql`: FAILED heads, FAILED/NEW `created_at` ties both id orders, mixed `next_retry_at`,
  history + COMPLETED after NEW, 128 empty partitions), with and without `key_ties=1`, custom **and** generic plans,
  parts 64/256: `results/G1-edge/equivalence.txt` (driver `run-edge.sh`).
- With `key_ties=1` the original query itself returns tied keys in plan-dependent order: H01 (identical predicate set)
  and H02 (index only) already show PASS-TIES vs the original in 2 of 16 checks (generic plans / parallel 256p plans).

## Regression: other queries whose plan changed; write cost; index size
Full baseline suite with both indexes applied post-seed (`results/H04-reg-{A,B}/`) vs `results/base-{A,B}/`:
- Plan changes: original-SQL filter queries switch their inner side to `incomplete_key` (faster, as in H02);
  B `findIncompleteRecordPages` generic keys=100 6.6 → 1.4 ms (now only `record_key_completed_created`) and keys=1000
  index order change (22 → 20 ms); B `findRecordKeysInPartitions_nofilter` 256p generic/generic-serial shape change
  within +3%. `incomplete_created` is not picked by any unmodified query (`findOldestCreatedAtByPartitions`, cleanup
  selects and `findPendingRecords` keep their plans).
- Slower >10% with unchanged plan (noise): A `findOldestCreatedAtByPartitions` 256p generic 11.1 → 13.0 ms (same in
  H02/H03 runs).

Write cost (`results/G1-wc-{A,B}/{base,H04}.txt`, both indexes built incrementally before seeding):

| | insert ms/1k | insert WAL B/op | update ms/1k | update WAL B/op |
|---|---|---|---|---|
| A control → H04 | 25.9 → 29.3 (+13%) | 2449 → 2764 (+13%) | 36.1 → 38.0 (+5%) | 3307 → 3304 (0%) |
| B control → H04 | 26.1 → 29.8 (+14%) | 2436 → 2758 (+13%) | 35.1 → 36.7 (+5%) | 3328 → 3332 (0%) |

Index size: `incomplete_created` A 81 pages (0.6 MB), B 2,545 pages (20 MB, incremental and post-seed alike), D 2 MB;
plus `incomplete_key` (A 0.7 MB, B 18 MB). After the 50k write cycle without vacuum: A 707 + 749 pages, B 3,171 +
3,016 pages. Total index pages B 39,706 → 44,610 (+12%).

### Experimenter note
H04 is the clear winner: the key query drops from 266/428 ms (B, 64/256p, serial) to 0.2/0.36 ms, D from 38/139 to
0.09 ms, A/E to ≤0.6 ms, generic plans included, with the cost now proportional to the batch (≈2.5 ms for 1000) rather
than to the backlog. Equivalence is exact except for the documented cross-key tie order, which the original does not
define either. Open issues for refinement: (1) the D filter-off custom plan misestimate (4.7 → 14 ms), (2) the
no-due-head worst case is a full scan of the partial index (15 ms at 200k incomplete rows), (3) +13% insert WAL /
+5% update time for two partial indexes, close to the 15% budget — Phase 2 should check whether `incomplete_key`
can replace `record_key_completed_created`/`record_key_created` to pay for it, and (4) per-dialect LIMIT syntax and
no partial indexes on MySQL/Oracle (Phase 3). H01's bound is part of this SQL and is what makes the probe an index
range.

## Decision + reasoning
_pending_


## Orchestrator decision
refined→H05. D filter-off regresses 4.7→14 (misestimate); insert +13–14% (ledger: `../STATE.md`; final report: `../99-final-report.md`)
