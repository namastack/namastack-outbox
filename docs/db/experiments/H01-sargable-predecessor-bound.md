# H01: Sargable predecessor bound in the anti join (query-only, existing indexes)
Status: measured
Target queries: `findRecordKeysInPartitions` (filter on), `findOldestDueNextRetryAtByPartitions` (filter on)

## Change

No DDL. One redundant predicate in the `NOT EXISTS` of both filter queries
(`JdbcOutboxRecordRepository.recordKeysQueryWithPreviousFailureFilterTemplate` and
`oldestDueNextRetryAtQueryWithPreviousFailureFilter`):

```sql
SELECT o.record_key
FROM outbox_record o
WHERE o.partition_no IN (:partitions)
  AND o.status = :status
  AND o.next_retry_at <= :now
  AND NOT EXISTS (
    SELECT 1 FROM outbox_record older
    WHERE older.record_key = o.record_key
      AND older.completed_at IS NULL
      AND older.created_at <= o.created_at          -- added (implied by the OR below)
      AND (
        older.created_at < o.created_at
        OR (older.created_at = o.created_at AND older.id < o.id)
      )
  )
GROUP BY o.record_key
ORDER BY MIN(o.created_at) ASC
```

`findOldestDueNextRetryAtByPartitions` (filter on): same `NOT EXISTS`, outer `SELECT MIN(o.next_retry_at)`
unchanged.

Harness files: `experiments/H01/queries/findRecordKeysInPartitions_filter.sql`,
`experiments/H01/queries/findOldestDueNextRetryAtByPartitions_filter.sql` (same names as the baseline files, so
`run-suite.sh -q` labels line up with `results/base-*`).

## Rationale / expected plan

Today the predecessor condition is a single OR, which Postgres cannot turn into an index range. The probe on
`idx_outbox_record_record_key_completed_created (record_key, completed_at, created_at)` therefore has
`Index Cond: record_key = o.record_key AND completed_at IS NULL` only and checks the OR as a join filter on
**every incomplete row of the key** (baseline D: 358k rows removed by the join filter at 64 partitions,
1.9M at 256; cost quadratic in incomplete rows per key). In D/B the planner even prefers a hash anti join whose
inner side is all incomplete rows of the table (`results/base-D/findRecordKeysInPartitions_filter-parts64-costs.txt`,
`results/base-B/findRecordKeysInPartitions_filter-parts64-serial.txt`).

With `older.created_at <= o.created_at` the existing index gets a three-column condition
`record_key = o.record_key AND completed_at IS NULL AND created_at <= o.created_at`: `IS NULL` is an equality-like
btree qual, so the range on the third column is contiguous. Expected plan: `Nested Loop Anti Join` with the outer
`idx_status` scan unchanged and an inner `Index Scan using idx_outbox_record_record_key_completed_created`; each
probe stops at the first predecessor (non-head rows: first entry; head rows: the range is empty apart from
`created_at` ties). Probe cost becomes O(1) per outer row instead of O(incomplete rows of the key), and the planner's
estimate of rows per probe shrinks (range selectivity), which favours the nested loop over the hash.

Expected effect:
- D: join-filter removals drop from 358k/1.9M to ~0; 38 ms -> ~3-6 ms (64p), 141 -> ~8-12 ms (256p).
- A/E: already NL anti join; small gain (fewer rows filtered; 5.0 -> ~3-4 ms).
- B/C: uncertain. If the planner switches to NL, B pays 49k probes (~3 buffers each, ~150k hits) on top of the
  ~56 ms outer scan: ~100-150 ms serial (from 265). If it keeps the hash anti join (inner = all incomplete rows via a
  seq/bitmap scan), there is no gain. This hypothesis isolates how much of the gain needs no DDL at all.

## Semantic equivalence argument

`(a < b) OR (a = b AND x < y)` implies `a <= b`, so the added conjunct never removes a row the OR accepts; the
predicate set and therefore the result set and order are unchanged. Null handling: `created_at` is `NOT NULL`.
Holds for every `status` argument and for both queries.

## Portability

- postgres: plain SQL; uses the existing `record_key_completed_created` index.
- mysql / mariadb: same index `(record_key, completed_at, created_at)` (InnoDB appends `id`); `completed_at IS NULL`
  is a `ref_or_null`/range-friendly qual and `created_at <=` extends the range. Expected to help the same way
  (MySQL currently has the same OR problem). No partial indexes needed.
- sqlserver: same index works (`IS NULL` seek + range on the next column).
- oracle: composite index with non-null leading `record_key` stores NULL `completed_at` entries, so
  `completed_at IS NULL` can be an access predicate; range on `created_at` follows.
- jpa: same predicate in the JPQL `recordKeysQueryWithPreviousFailureFilter` / oldest-due JPQL
  (`and older.createdAt <= o.createdAt`); no entity change.
- mongo: separate aggregation pipeline (`buildStrictFifoAggregation`); analogous `$lte` bound in the lookup
  sub-pipeline, Phase 3.

## Experiment plan

1. Scenarios A, B, C, D, E; `parts=64` and `parts=256`; existing schema (no `--ddl`).
   `./run-suite.sh H01-<S> <S> -q ../experiments/H01/queries --skip-writes --keep`
   (runs custom, generic, serial, generic-serial and costs for the two files).
2. Report `serial` for the key query (that is what pgjdbc runs) and `custom` for the MIN query (no `withMaxRows`,
   may use workers). Before -> after against `results/base-<S>/summary.tsv`. Note whether B/C switch from
   hash to nested-loop anti join, and the `Rows Removed by Join Filter`.
3. Generic plan: compare `generic` / `generic-serial` with baseline (872 ms B/256 baseline generic-serial).
4. Equivalence (5.5): on each kept container and on an edge container
   (`./start-postgres.sh H01e edge`; for cross-key ties a second one:
   `./start-postgres.sh H01t none` then `docker exec -i outbox-db-H01t psql -U postgres -v key_ties=1 < seed-edge.sql`
   and `ANALYZE`):
   `./compare-results.sh outbox-db-H01-<S> queries/findRecordKeysInPartitions_filter.sql ../experiments/H01/queries/findRecordKeysInPartitions_filter.sql --var parts=64`
   and `--var parts=256`; same for `findOldestDueNextRetryAtByPartitions_filter`. Expected PASS everywhere
   (identical predicate set; tie order across keys is plan-dependent in both, see dossier).
5. No regression run needed (no index change).

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

**custom-serial** — ms (shared hits), ctl → H01

| Query | parts | A | B | C | D | E |
|---|---|---|---|---|---|---|
| keys on | 64 | 5.27 (7,374) → 5 (6,450) | 266 (106,225) → 270 (106,225) | 132 (33,368) → 115 (33,368) | 38.1 (7,273) → 35.6 (7,273) | 5.71 (7,287) → 5.59 (6,449) |
| keys on | 256 | 20.3 (27,806) → 18.8 (24,206) | 428 (106,225) → 381 (106,225) | 208 (33,368) → 208 (33,368) | 139 (7,273) → 128 (7,273) | 19.9 (26,509) → 19.2 (23,309) |
| MIN on | 64 | 4.89 (7,374) → 4.6 (6,450) | 267 (106,225) → 266 (106,225) | 124 (33,368) → 108 (33,368) | 37.1 (7,273) → 35.5 (7,273) | 5.24 (7,287) → 5.02 (6,449) |
| MIN on | 256 | 17.6 (27,806) → 16.4 (24,206) | 364 (106,225) → 353 (106,225) | 178 (33,368) → 176 (33,368) | 139 (7,273) → 131 (7,273) | 18.3 (26,509) → 17.5 (23,309) |

**generic-serial** — ms (shared hits), ctl → H01

| Query | parts | A | B | C | D | E |
|---|---|---|---|---|---|---|
| keys on | 64 | 10.5 (7,374) → 10.4 (6,450) | 234 (106,009) → 159 (213,352) | 107 (33,118) → 149 (216,290) | 54.9 (7,273) → 52.3 (7,273) | 10.9 (7,287) → 10.6 (6,449) |
| keys on | 256 | 49.6 (9,678) → 49.9 (9,678) | 915 (106,225) → 879 (106,225) | 787 (34,088) → 789 (34,088) | 197 (7,273) → 185 (7,273) | 50.6 (9,693) → 51 (9,693) |
| MIN on | 64 | 10 (7,374) → 10.8 (6,450) | 228 (106,009) → 148 (213,352) | 88.9 (33,118) → 135 (216,290) | 55.2 (7,273) → 52.1 (7,273) | 11.7 (7,287) → 10.2 (6,449) |
| MIN on | 256 | 49.4 (9,678) → 48.2 (9,678) | 863 (106,225) → 864 (106,225) | 761 (34,088) → 757 (34,088) | 198 (7,273) → 184 (7,273) | 48.8 (9,693) → 48.6 (9,693) |

**custom** — ms (shared hits), ctl → H01

| Query | parts | A | B | C | D | E |
|---|---|---|---|---|---|---|
| keys on | 64 | 5.31 (7,374) → 5.02 (6,450) | 169 (106,367) → 182 (106,371) | 63.5 (33,368) → 56.1 (33,368) | 38 (7,273) → 37.1 (7,273) | 5.53 (7,287) → 5.42 (6,449) |
| keys on | 256 | 15.9 (27,864) → 14.9 (24,264) | 237 (106,372) → 222 (106,371) | 102 (33,368) → 97.6 (33,368) | 140 (7,273) → 130 (7,273) | 15.6 (26,567) → 14.8 (23,367) |
| MIN on | 64 | 4.88 (7,374) → 4.64 (6,450) | 162 (106,369) → 170 (106,365) | 62.8 (33,368) → 54.1 (33,368) | 37.9 (7,273) → 37 (7,273) | 5.18 (7,287) → 5.05 (6,449) |
| MIN on | 256 | 15.4 (27,848) → 14.6 (24,248) | 211 (106,370) → 217 (106,369) | 85.5 (33,368) → 82.6 (33,368) | 139 (7,273) → 130 (7,273) | 15.6 (26,551) → 15.1 (23,351) |

Plan shapes (custom-serial, key query filter on): the bound becomes an index condition only where the planner already
chose a nested-loop anti join (A/E: `Index Cond: record_key = o.record_key AND completed_at IS NULL AND created_at <= o.created_at`,
buffers −12%). **No plan switches to a nested loop**: B stays parallel-capable hash anti join with a seq scan of the
heap as the inner side (106k buffers, unchanged), D stays hash anti join with a bitmap heap scan inner side
(358k / 1.9M rows removed by join filter, unchanged), C stays seq scan + hash. In a hash/merge join the bound is just
another join filter. Changes elsewhere are within noise (B 256p −11% / C 64p −13% with identical buffers).

Generic (`generic-serial`): same picture, e.g. B 64p 234 → 159 ms but with *more* buffers (213k, a different NL plan
over a bitmap scan of `partition_status_retry`); B 256p 915 → 879 ms. No generic plan got worse beyond noise.

Worst case (`partlist_override=999`) is 0.04–0.07 ms in every state (planner sees the partition is empty) and is not
discussed further.

## Equivalence check: PASS/FAIL + method
**PASS.** Scenarios A–E × parts 64/256 × both filter queries: exact PASS (20/20). Edge seed: exact PASS everywhere
except 1 PASS-TIES (key_ties=1, generic, 256p), caused by plan-dependent tie order of the original.
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
Not applicable (query-only). Note from the H02 regression runs: with the H02 index present, the **original** SQL
(without the H01 bound) gets the same plans and timings as the H01 SQL (B 64p serial 149 vs 148 ms, 256p 256 vs 248 ms;
A 3.4 / 5.2 ms), so the bound adds nothing measurable on top of H02 either.

### Experimenter note
H01 alone is close to a no-op: it only helps where Postgres already runs a nested-loop anti join (A/E, −5 to −10%),
and it does not change the planner's choice in B/C/D, where the cost lives (B 266/428 ms serial unchanged within
noise, D −7%). It is semantically safe (exact PASS everywhere) and harmless, and probably worth keeping as part of
H04's SQL for databases without partial indexes (MySQL/Oracle use the existing `(record_key, completed_at, created_at)`
index for NL probes), but on Postgres it is not a lever by itself and with H02 present it is redundant.

## Decision + reasoning
_pending_


## Orchestrator decision
rejected. no plan change on PG; adds nothing with H02 index (ledger: `../STATE.md`; final report: `../99-final-report.md`)
