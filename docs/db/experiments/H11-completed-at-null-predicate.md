# H11: Add `AND completed_at IS NULL` to the bulk load (no DDL, uses `record_key_completed_created`)
Status: measured
Group: G2 (iteration 1)
Target queries: `findIncompleteRecordPages` (hot), `findIncompleteRecordsByRecordKey` (SPI).

## Change

No DDL. Rewritten SQL (`H11/queries/`):

```sql
SELECT * FROM outbox_record
WHERE record_key IN (:recordKeys) AND status = :status AND completed_at IS NULL
ORDER BY record_key, created_at, id;

SELECT * FROM outbox_record
WHERE record_key = :recordKey AND status = :status AND completed_at IS NULL
ORDER BY created_at, id;
```

Code diff (described only, not applied):
```diff
-        WHERE record_key IN (:recordKeys) AND status = :status
+        WHERE record_key IN (:recordKeys) AND status = :status AND completed_at IS NULL
```
(same for `findByKeyAndStatusQuery`; JPA: `and o.completedAt is null`; Mongo: `.and("completedAt").is(null)`).

## Rationale / expected plan

The existing index `record_key_completed_created (record_key, completed_at, created_at)` already separates
incomplete rows (`completed_at IS NULL` sorts last within a key, and `IS NULL` is a B-tree index condition), but
today's predicate only constrains `record_key`, so a probe returns the whole key history (20 rows per key in
A/B, 1000 in D) and the planner intersects it with the 200k-entry `status` bitmap (B) or switches to the
`status` index (D). With the extra predicate the probe is `record_key = ANY(...) AND completed_at IS NULL`
and returns only the incomplete rows (NEW + the rare FAILED, removed by a cheap filter). Expected plans:

- A/B/C: `Sort (Bitmap Heap Scan (Bitmap Index Scan record_key_completed_created, Index Cond: record_key = ANY AND completed_at IS NULL), Filter status)`
  — no BitmapAnd, no `status` bitmap. Or `Incremental Sort (presorted: record_key) (Index Scan record_key_completed_created)`:
  the index order is `(record_key, completed_at, created_at)`; Postgres does not treat `IS NULL` as a constant
  for pathkeys, so only `record_key` is presorted and each key group (<= 200 rows) is sorted in memory.
- D: either bitmap + full Sort (spill remains, but no rows removed by filter and fewer index pages), or the
  Incremental Sort path, which removes the spill (per-key groups of 200 rows ≈ 130 kB). Which one the planner
  picks depends on the random heap fetch estimate (see H10 caveat); measure with `random_page_cost=1.1` and
  `enable_bitmapscan=off` too.

Expected (custom, ms, 10/100/1000 keys): B 0.2/6.6/22 → ~0.1/1/6 (no 200k-entry status bitmap); A, E ~unchanged
to slightly better (A already ~0.3/0.8/4); C ~unchanged (no COMPLETED rows); D 40 → 30 (bitmap) or ~10–15
(incremental sort). Zero write cost and zero index size: if the gain is close to H10/H12 it is the cheapest change.
Generic plans: `completed_at IS NULL` is a literal, so the generic plan gets the same index condition.

## Semantic equivalence

Equivalent iff every row with `status = 'NEW'` has `completed_at IS NULL`. The library guarantees this:
`OutboxRecord.markCompleted` is the only place that sets `completedAt` (together with `status = COMPLETED`);
records are created with `completedAt = null` (`OutboxRecord` builder); `markFailed` and the retry path do not touch
`completedAt`; `OutboxRecordProcessor.completeRecord` restores both fields together on failure; and there is no
COMPLETED → NEW transition. The invariant is not enforced by the schema (no CHECK constraint), so rows written
by hand or through the generic `update(record)` SPI with inconsistent fields would be excluded by the rewrite.
This should be documented next to the query; optionally the ADR can add a CHECK constraint
`CHECK (status = 'COMPLETED' OR completed_at IS NULL)` (the planner does not use CHECK constraints for index
matching, so this is documentation/safety only, and it costs a little on writes).

Check: `compare-results.sh` original vs `H11/queries/*` on A–E (keys 10/100/1000) and on the edge seed
(all patterns, ties included) → expect PASS. Additionally confirm the invariant holds in every seed:
`SELECT count(*) FROM outbox_record WHERE status = 'NEW' AND completed_at IS NOT NULL` = 0.
Negative control (optional): insert one NEW row with a non-null `completed_at` into the edge seed and confirm
the comparison FAILs, to show the precondition is real.

## Portability

All dialects accept the predicate. MySQL/MariaDB/Oracle/SQL Server: `record_key_completed_created` exists in every
DDL; `IS NULL` is a range/seek condition on InnoDB and SQL Server B-trees. Oracle: single-column NULLs are not
indexed, but in a composite index with non-null `record_key` the entry exists, so `completed_at IS NULL` is an
access predicate. JPA: index `idx_outbox_record_key_completed_created` exists (`recordKey, completedAt, createdAt`).
Mongo: `record_key_completed_created_idx {recordKey, completedAt, createdAt}` exists; `completedAt: null` also
matches a missing field, which is the same as SQL NULL here.

## Experiment plan

Scenarios A, B, C, D. Container `outbox-db-H11`, baseline schema (no DDL).
1. `./start-postgres.sh H11 <S>`.
2. `./run-explain.sh -c outbox-db-H11 -s H11-<S> -q ../experiments/H11/queries`, plus `--generic` and `--costs -r 1`.
3. D: add `--set random_page_cost=1.1 --tag rpc11` and `--set enable_bitmapscan=off --tag nobitmap`.
4. Equivalence (5.5): for each S and keys in 10/100/1000:
   `./compare-results.sh outbox-db-H11 queries/findIncompleteRecordPages.sql ../experiments/H11/queries/findIncompleteRecordPages.sql --var keys=<n>`
   (and `--generic`), `findIncompleteRecordsByRecordKey` likewise; then on the edge seed (`start-postgres.sh H11e edge`).
5. No regression run needed (no index change).

## Results (per scenario): plan shape, indexes used, median ms, buffers, before → after

Method: `experiments/G2-iter1/run-scenario.sh`, baseline schema, same container for before (`results/G2ctl-<S>`) and
after (`results/H11-<S>`); 1 warm-up + 3 runs, median, shared hits in parentheses, `spill` = external sort.
rkcc = `record_key_completed_created`.

| S | query | mode | before ms (hit) | after ms (hit) | Δ | after plan [indexes] |
|---|---|---|---|---|---|---|
| A | pages 10 | custom | 0.32 (42) | 0.08 (70) | -76% | IncSort(Index Scan) [rkcc] |
| A | pages 100 | custom | 0.86 (335) | 0.41 (700) | -52% | IncSort(Index Scan) [rkcc] |
| A | pages 1000 | custom | 4.21 (510) | 4.59 (510) | +9% | Sort(Index Scan) [status] |
| A | byKey | custom | 0.29 (13) | 0.04 (7) | -86% | Sort(Index Scan) [rkcc] |
| A | pages 10 | generic | 0.14 (65) | 0.09 (70) | -34% | IncSort(Index Scan) [rkcc] |
| A | pages 100 | generic | 0.89 (335) | 0.44 (700) | -51% | IncSort(Index Scan) [rkcc] |
| A | pages 1000 | generic | 11.32 (3293) | 5.12 (6072) | -55% | IncSort(Index Scan) [rkcc] |
| A | byKey | generic | 0.05 (23) | 0.04 (7) | -11% | Sort(Index Scan) [rkcc] |
| B | pages 10 | custom | 0.18 (68) | 0.15 (48) | -13% | Sort(BHS(BIS)) [rkcc] |
| B | pages 100 | custom | 6.53 (531) | 1.23 (381) | -81% | Sort(BHS(BIS)) [rkcc] |
| B | pages 1000 | custom | 22.49 (3864) | 24.71 (3803 spill) | +10% | Sort(BHS(BitmapAnd(BIS,BIS))) [status,rkcc] |
| B | byKey | custom | 0.05 (23) | 0.04 (12) | -7% | Sort(Index Scan) [rkcc] |
| B | pages 10 | generic | 0.21 (68) | 0.11 (120) | -47% | IncSort(Index Scan) [rkcc] |
| B | pages 100 | generic | 6.62 (531) | 1.36 (381) | -79% | Sort(BHS(BIS)) [rkcc] |
| B | pages 1000 | generic | 21.94 (3864) | 24.70 (3803 spill) | +13% | Sort(BHS(BitmapAnd(BIS,BIS))) [status,rkcc] |
| B | byKey | generic | 0.05 (23) | 0.04 (12) | -20% | Sort(Index Scan) [rkcc] |
| D | pages 10 | custom | 3.96 (464) | 3.50 (405) | -12% | Sort(BHS(BitmapAnd(BIS,BIS))) [rkcc,status] |
| D | pages 100 | custom | 39.87 (1858 spill) | 39.92 (2274 spill) | +0% | Sort(BHS(BitmapAnd(BIS,BIS))) [status,rkcc] |
| D | pages 1000 | custom | 39.82 (1858 spill) | 40.61 (2274 spill) | +2% | Sort(BHS(BitmapAnd(BIS,BIS))) [status,rkcc] |
| D | byKey | custom | 1.09 (227) | 0.25 (205) | -77% | Sort(BHS(BIS)) [rkcc] |
| D | pages 10 | generic | 6.33 (536) | 2.95 (389) | -53% | Sort(BHS(BIS)) [rkcc] |
| D | pages 100 | generic | 44.33 (1904 spill) | 40.65 (2258 spill) | -8% | Sort(BHS(BIS)) [rkcc] |
| D | pages 1000 | generic | 44.43 (1904 spill) | 41.19 (2258 spill) | -7% | Sort(BHS(BIS)) [rkcc] |
| D | byKey | generic | 0.78 (1009) | 0.37 (205) | -52% | Sort(BHS(BIS)) [rkcc] |

C: unchanged (C has no COMPLETED rows, rkcc already holds only incomplete rows): pages 100 0.68 → 0.73, 1000 11.8 → 12.1.
E ≈ A: pages 100 custom 0.83 → 0.41, 1000 custom 4.15 → 4.34, generic 1000 11.2 → 5.0.

Diagnostics (custom, before → after with the same setting): B 1000 keys `enable_bitmapscan=off` 32.3 → 11.2 ms (IncSort
over rkcc, no spill), `random_page_cost=1.1` 24.2 → 23.5 (BitmapAnd + spill kept); B 100 rpc11 6.6 → 0.63. D 100/1000:
rpc11 40.1 → 39.4 (unchanged, spill); nobitmap 41.3 → 41.4 (planner takes `status` index + Sort); generic + nobitmap
44.0 → 19.7 ms (IncSort over rkcc, 19.5k hits). With H10 present (`H11-<S>` mode `*-h10`): no additional gain.

Summary: helps the middle range with default settings: A/E pages 10/100 −50…−76 % (IncSort over rkcc instead of
BitmapAnd), **B pages 100 6.5 → 1.2 ms (−81 %)** because the 200k-entry `status` bitmap disappears, every generic case
in A/B (A 1000 11.3 → 5.1), byKey −7…−86 %. It does not help large lists: **B pages 1000 22.5 → 24.7 ms (+10 %)** — the
planner still ANDs with the `status` bitmap and, without the parallel plan of the baseline, the 5 MB sort now spills
(+ ~2 ms); D unchanged (~40 ms, spill).

## Equivalence check: PASS + method
`experiments/G2-iter1/eq.sh`: ordered md5 of the full result of the rewritten file vs the original file (control state,
same data). A, B, C, D, E × keys 10/100/1000 × custom/generic, plus `findIncompleteRecordsByRecordKey` custom/generic:
**40/40 PASS**. Edge seed (key_ties 0/1; ties on created_at, FAILED heads, not-due NEW): **16/16 PASS**. Invariant check
`status='NEW' AND completed_at IS NOT NULL` = 0 in every seed. Negative control (edge seed, one NEW row of the first
requested key given a `completed_at`): H11 **FAIL-ROWS** (row dropped, 885 → 884), as expected — the rewrite is only as
safe as the NEW ⇒ completed_at NULL invariant. (The first negative-control attempt in `G2-eq-edge.txt` targeted a key
that the edge seed does not create and updated no row; the rerun below it is the valid one.)
Files: `results/G2-eq-{A,B,C,D,E,edge}.txt`.

## Regression
No DDL, no write cost. Only the two target queries change.

## Experimenter note
H11 is equivalent (under the invariant) and free, and gives the largest default-settings gain of the zero-DDL options at
small/medium key lists (B 100 keys −81 %, generic plans −50 %+). But the order it produces is only `record_key`
(Incremental Sort; `IS NULL` is not treated as a constant for pathkeys), it still loses the large cases (B 1000 keys
+10 % with a new spill, D unchanged) and makes custom plans depend on the same row misestimate as H10/H12 (requested
keys are assumed to have the average NEW share). On its own it is a partial fix; it is mainly the predicate H12 needs.


## Orchestrator decision
rejected (alone). predicate reused in H12/iter2 (ledger: `../STATE.md`; final report: `../99-final-report.md`)
