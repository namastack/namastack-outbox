# R5 - why `pages` doubles with the full `(status, partition_no)` index (UABCD-L), and why D3 does not

Diagnosis only (no recommendation changed). PostgreSQL 18.6 (postgres:18-alpine, harness container, defaults: `random_page_cost=4`, `work_mem=4MB`, `jit=on`),
S5 snapshot regenerated with `bench gen` (seed 42, 500 000 rows, 199 980 NEW / 300 000 COMPLETED / 20 FAILED, 100 keys). Scratch schemas were built from `snap.rec`
exactly like `prepare` (DDL of `experiments/R5-<variant>/ddl.sql`, indexes after load, `VACUUM (FREEZE, ANALYZE)`), queried through `psql` with the 9 first NEW keys
(the `pages` SQL of `R5-*/queries/pages.sql`, literal values = custom plan). Times below are `EXPLAIN (ANALYZE, BUFFERS)` execution times, warm cache, median of 5-7 runs in one
session. Scripts: `/tmp/claude-1000/-home-zamo-git-namastack-outbox/d42f9bf0-f980-44e6-84c4-8250b4a2d3e0/scratchpad/{mk.sh,mk2.sh,mk3.sh,run.sh,tm.sh,gen.sh,ex.sh,q.sql}`.
Scratch schemas were dropped, the S7 snapshot was regenerated (`harness/build/snap-postgres.txt` identical to the state before).

Labels: **3a** = UABC-L, **3b** = UABCD-L (full `idx_outbox_record_status_partition`), **D3** = partial `idx_outbox_record_status_partition_incomplete ... WHERE completed_at IS NULL`.

## Summary

| | measured | inferred |
|---|---|---|
| Extra time of 3b | +70 ms = one `Bitmap Index Scan` on the full status index per key (199 980 TIDs x 9 loops); the BitmapAnd removes nothing | - |
| Why the planner picks it | AND is estimated to cut 1985 -> 788 rows (independence of `record_key` and `status`), real result 2000 rows; est. plan cost 47 981 vs 63 188, real 95 ms vs 25 ms. Extended statistics do not fix it | - |
| Why not D3 | D3's index is smaller and estimated cheaper, so cost is not the reason. Pattern: no AND whenever the partial status index and the partial key index share the predicate (or the status predicate implies the key predicate); AND appears when the predicates differ, even with identical index content (ev1: 1 458 176 bytes = D3, 95.5 ms) | exact rule in the planner (`choose_bitmap_and`) not read in source |
| D3 flip risk | not flipped by fewer NEW rows, bigger/smaller hot keys, generic plan, churn + stale stats. Protection is structural (index DDL), not a cost margin | - |

## 1. Where the extra time goes

Harness (`results/<v>/postgres/S8-S5-v1-R5/`): median `pages` 62.640 (3a), 132.509 (3b), 60.832 (D3) ms (`timings.csv`); `plantimes.csv` (EXPLAIN ANALYZE, planning/execution):
0.137/25.403 (3a), 0.151/94.045 (3b), 0.204/24.859 (D3). Delta 3b-3a: 69.9 ms in the harness median, 68.6 ms in EXPLAIN. So EXPLAIN reproduces the delta; the constant ~37 ms between
median and EXPLAIN is client-side transfer of 18 000 rows x ~1 KB (same for all variants). Planning is 0.15 ms (not the cause). `plancache.csv`: 16 of 20 timed reps ran on the
generic plan (custom 4 / generic 16 in all three variants); see section 4, the generic plan has the same shape, so it does not change the picture.

Warm EXPLAIN in this session (per loop = per key, 9 loops; `Heap Blocks` exact, no lossy):

| node (per loop) | 3a | 3b |
|---|---|---|
| Bitmap Index Scan `idx_outbox_record_key_created_id_incomplete` | 0.347 ms, 273 buf (total) | 0.344 ms, 273 buf (total) |
| Bitmap Index Scan `idx_outbox_record_status_partition` (`status='NEW'`) | - | **7.82 ms, rows=199 980, 1611 buf (179/loop)** |
| BitmapAnd | - | 8.30 ms (incl. both scans), 1884 buf |
| Bitmap Heap Scan (cumulative) | 1.37 ms, `Heap Blocks exact=17025` | 9.36 ms, `exact=17016`; heap part alone 9.36-8.30 = 1.06 ms |
| Sort (cumulative) | 2.40 ms | 10.42 ms |
| Execution | 26.4 ms (17 298 buf) | 97.1 ms (18 900 buf) |

The heap scan (1.37 - 0.35 = 1.02 ms vs 1.06 ms), the number of heap blocks (17 025 vs 17 016) and the sort are the same; the whole difference is the status bitmap scan:
7.8 ms x 9 = 70 ms, +1 602 buffers (all hits; 179 index pages read per loop, the NEW part of the full index). Cold first run is slower for both (3a 67.6, 3b 137 ms).
Separation (measured): in one transaction in 3b, `DROP INDEX idx_outbox_record_status_partition` -> plan without BitmapAnd, **25.1 ms** (= 3a).
Lossy bitmaps appear only below work_mem: 4 MB / 1 MB / 256 kB all `exact` (97.0 / 112.6 / 90.5 ms), 64 kB `exact=7124 lossy=9892` (101.6 ms).

## 2. Why the planner chooses BitmapAnd

3b, custom plan, `COSTS` only:

```
Nested Loop  (cost=5306.61..47980.96 rows=17865)
  Bitmap Heap Scan  (cost=2329.42..5197.65 rows=1985)
    BitmapAnd  (cost=2329.42..2329.42 rows=788)
      Bitmap Index Scan idx_..._key_created_id_incomplete (cost=0.00..127.31  rows=1985)
      Bitmap Index Scan idx_outbox_record_status_partition (cost=0.00..2200.87 rows=198460)
```

3a: `Nested Loop cost=6996.28..63188.16`, heap scan 127.83..6887.19, rows=1987. With `enable_bitmapscan=off` both pick an index scan on the key index, cost ~71 400 (3a 71 442, 3b 71 334).
So the AND saves 6760 - 2868 = 3 892 on the heap part and pays 2 200 for the index: est. total 47 981 vs 63 188 (-24 %).

The saving comes from the row estimate 788 = 1985 x 0.397: the planner multiplies the selectivities of the two bitmap paths (independence of `record_key` and `status`). The
real result of the AND is 2000 rows (every incomplete row of a hot key is NEW; heap blocks 17 016 vs 17 025). Estimated 788 vs actual 2000: misestimate, and the cost model
makes a plan that is 3.8x slower (95 vs 25 ms) look 24 % cheaper. Extended statistics do not repair it (measured): `CREATE STATISTICS (mcv) ON record_key, status, completed_at` +
`ANALYZE` on 3b -> BitmapAnd rows=804, same plan, 95.5 ms (the existing `(status, completed_at)` MCV is in all variants already). Higher `random_page_cost` keeps the AND (3b up to 50);
this is not a threshold effect around the default.

Aside (not investigated further): with `enable_bitmapscan=off` all variants run an `Index Scan` on the key index in ~10 ms (3a 9.6, 3b 10.0, D3 9.9), faster than the bitmap plan
chosen even for 3a/D3 (25 ms), but estimated more expensive (71 400 vs 63 200). A second misestimate of the same query, not related to the index in question.

## 3. Why D3 does not get the same plan

Sizes and estimates (S5, same table and statistics):

| | full index (3b) | partial (D3) |
|---|---|---|
| index size | 3 653 632 B (446 pages) | 1 458 176 B (178 pages) |
| standalone `status='NEW'` bitmap scan estimate | cost 2200.87, rows 198 460 | cost 891.30, rows 80 401 (actual 199 980) |

D3's index is smaller and estimated cheaper, so an AND with it would look even better (fewer rows, lower cost); cost, index size, correlation or statistics (same table, same stats) do not
explain the difference. The row estimate 80 401 is the partial-index selectivity (0.4 x predicate counted twice), also wrong, but it makes the AND more, not less, attractive.

Separation experiments (3 other index-set variants on the same data; key index = `(record_key, created_at, id)`; the other two partial status indexes, `..._status_created_id_incomplete` and `..._status_retry_incomplete`, are present in all):

| key index | status_partition index | BitmapAnd? | median ms |
|---|---|---|---|
| partial `completed_at IS NULL` | none (3a) | no | 25.3 |
| partial `completed_at IS NULL` | full (3b) | yes | 95.4 |
| partial `completed_at IS NULL` | partial `completed_at IS NULL` (D3) | **no** | 25.3 |
| full | partial `completed_at IS NULL` (ec) | yes | 100.5 |
| full | full (ed; AND used the partial `status_retry` index) | yes | 99.2 |
| partial `completed_at IS NULL` | partial `WHERE status <> 'COMPLETED'` (ev1, same 200 000 entries, 1 458 176 B as D3) | **yes** | 95.5 |
| partial `completed_at IS NULL` | partial `completed_at IS NULL AND status <> 'COMPLETED'` (ev2) | no | 26.3 |
| partial `status <> 'COMPLETED'` | partial `status <> 'COMPLETED'` (ev3; the AND used the `status_retry` partial index, other predicate) | yes (other index) | - |

ev1 is the forced BitmapAnd with D3's index content (identical bytes and entries, only the predicate text differs): **95.5 ms**, same as 3b (95.4). That is D3's real cost when the
AND is allowed. D3 itself never gets it, at `random_page_cost` 4, 6, 10, 20, 50, 100 (3b keeps it up to 50). Also in 3a and D3 the partial `status_created`/`status_retry` indexes (same
predicate as the key index) are never ANDed, but they are in ed/ev3.

Measured pattern: the AND is built when the status-index path and the key-index path have different partial predicates (or one is a full index); it is not built when the status path's
predicate equals (D3, 3a's partial status indexes) or implies (ev2) the key index's predicate. Inferred (planner source not read here): the planner skips ANDing paths whose partial
predicates make one redundant for the other; the estimated costs show it is not a cost decision. Only the pattern is established.

## 4. Does D3 flip under other conditions?

Tested on D3 (partial key + partial status) and, as stand-in for "what D3 would do if the AND were allowed", ev1 (identical content, differing predicate text). Data shapes built from the S5 snapshot:

| shape | NEW rows | D3 plan (est. cost, ms) | ev1 (AND-allowed) plan (est. cost, ms) |
|---|---|---|---|
| S5 | 199 980, 2000/key | key bitmap + filter (63 688, 25.3) | BitmapAnd (48 717, 95.5) |
| A: 1/20 of NEW | 10 050, ~100/key | plain Index Scan on key index (3 459, **0.84**) | BitmapAnd (1 303, **4.87**) |
| B: 10 hot keys x 20 000 | 199 900 | key bitmap + filter (64 261, 429) | BitmapAnd (48 440, 478) |
| C: ~1 NEW per key (92) | 92 | Index Scan on status partial/D3 index (74, 0.39) | Index Scan on status index (74, 0.32) |

- D3 never uses BitmapAnd in any shape. If it were allowed, the estimated gap would be 24-62 % in favour of the AND while reality is 3.8-5.8x slower (S5, A); in B the AND is 11 % slower.
- Generic plan (`plan_cache_mode=force_generic_plan`, 9 key params + `$10` status): 3a and D3 `BitmapAnd=0`, 3b keeps it (est. 40 181); first execution 650-770 ms (parse/JIT/cold), later 25-26 ms (3a, D3) and 95-115 ms (3b); custom plan on D3: 25-32 ms.
- After churn (70 % of NEW updated to COMPLETED, autovacuum off, no ANALYZE): D3 no AND (stale stats est. 81 220, 14.1 ms); after `ANALYZE` no AND (21 314, 16.4 ms), after `VACUUM (ANALYZE)` no AND (21 248, 7.8 ms). Not the real S9 workload.

So no tested statistic, data shape or plan mode moves D3 across the line: there is no cost margin to quantify, because the AND path is not built. The risk is DDL, not data: D3 is
safe only while the key index and the status partial index carry the same `completed_at IS NULL` predicate (ev1/ec/ed: change either one and the doubled time returns, in every shape
except tiny C).

## Open

- Planner mechanism for the missing AND with equal predicates is inferred from 8 index sets, not from the source of `choose_bitmap_and`; version tested 18.6 only.
- Not run: real S9 churn, S10 concurrency, S8-S5 harness itself (EXPLAIN in `psql`, 9 first keys, not the harness's owned-partition key set), other PostgreSQL versions.
- The key-index Index Scan (10 ms) vs bitmap plan (25 ms) misestimate is a separate finding; it would speed up `pages` on every variant if reachable (not investigated).
- ev2/ev3/ec/ed plans were read from the console output; their schemas are dropped, scripts reproduce them (`mk2.sh`).
