# H10: Ordered non-partial index `(record_key, status, created_at, id)` for the bulk load
Status: measured
Group: G2 (iteration 1)
Target queries: `findIncompleteRecordPages` (hot), `findIncompleteRecordsByRecordKey` (SPI); side effect on
`deleteByRecordKeyAndStatus` (admin).

## Change

No query rewrite (SQL as in `JdbcOutboxRecordRepository.findByKeysAndStatusQuery` / `findByKeyAndStatusQuery`).

```sql
-- H10/indexes-replace.sql (main variant: replaces the unused record_key_created index)
CREATE INDEX idx_outbox_record_record_key_status_created
    ON outbox_record (record_key, status, created_at, id);
DROP INDEX idx_outbox_record_record_key_created;
```

Variants for the experiment:
- `H10/indexes.sql`: add only (keeps `record_key_created`), to isolate the index effect from the drop.
- `H10/indexes-noid.sql`: `(record_key, status, created_at)` + drop. Smaller index; ties on `created_at` are then
  resolved by an Incremental Sort with presorted keys `record_key, created_at` (tiny groups).

## Rationale / expected plan

Today every plan has a full `Sort` on top of a bitmap or `status`-index scan (01-baseline.md): no index has
`status` and the ordering columns together, so the planner either intersects `status` (200k entries in B) with a
`record_key` index (BitmapAnd, 6.6 ms for 100 keys in B, of which most is building the 200k-entry `status` bitmap)
or walks all `NEW` entries of the `status` index and filters by key (D, 20k rows), then sorts. In D the sort of
19k wide rows (`SELECT *`, 500-byte payload, ~12 MB) exceeds `work_mem=4MB` and spills (40 ms).

With `(record_key, status, created_at, id)` both predicates are index conditions
(`record_key = ANY('{...}') AND status = 'NEW'`), only `NEW` rows of the requested keys are visited, and because
`status` is fixed by equality and the `ScalarArrayOp` is on the leading column, a plain B-tree Index Scan returns
rows in `(record_key, created_at, id)` order (Postgres 17+ SAOP scans keep index order). Expected plan:

```
Index Scan using idx_outbox_record_record_key_status_created on outbox_record
  Index Cond: ((record_key)::text = ANY ('{...}'::text[])) AND ((status)::text = 'NEW'::text)
```
no Sort node, no BitmapAnd, no rows removed by filter. Works identically in generic plans (status is an index
condition, not a partial-index predicate), so it is safe for pgjdbc server-side prepared statements.

Cost-model caveat (important for D): an ordered Index Scan does one heap visit per row. `record_key` has ~0
correlation with the heap (heap is in `created_at` order), so the planner prices 19k random heap fetches at
`random_page_cost=4`, while the bitmap path visits ~1.9k heap pages once and pays a sort. The planner may therefore
keep `Bitmap Heap Scan (new index) + Sort` in D (still better than today: no `status` bitmap, no filter, but the
spill stays). Measure D also with `random_page_cost=1.1` (typical SSD setting) and with `enable_bitmapscan=off`
to get the real runtime of the ordered path. On A/B/C (<= 8 rows per key) the ordered Index Scan should win on cost.

Expected (custom, ms, 10/100/1000 keys): A 0.3/0.8/4.0 → ~0.1/0.4/2.5; B 0.2/6.6/22 → ~0.1/0.7/5;
C 0.1/0.7/12 → ~0.1/0.5/4; D 3.9/40/40 → ~1.5/8–12/8–12 if the ordered path is chosen (no spill), else
~2/30/30 (bitmap on the new index + spilling sort). `findIncompleteRecordsByRecordKey`: IncSort/BitmapAnd → plain
Index Scan, no Sort.

## Semantic equivalence

Pure index change: the SQL text is unchanged, so rows and order are identical by definition (ORDER BY is
explicit). The check is still run to catch harness mistakes.

## Portability

- postgres: plain B-tree, fine. Index size estimate ~95–105 MB at 1M rows (id is a 32-char md5 string; vs 56 MB for
  `record_key_created`); the `noid` variant ~65 MB.
- mysql / mariadb (InnoDB): secondary indexes carry the PK implicitly, so `(record_key, status, created_at)` is
  already `(record_key, status, created_at, id)`: use the 3-column form. `record_key IN (...) AND status = ?
  ORDER BY record_key, created_at, id` is a multi-range scan in index order; check `EXPLAIN FORMAT=TREE` for the
  absence of a filesort. Key length: `record_key VARCHAR(255)` + `status VARCHAR(20)` + datetime + `id VARCHAR(255)`
  in utf8mb4 = ~2.1 kB, below the 3072-byte InnoDB limit.
- sqlserver: index key size limit 1700 bytes for nonclustered indexes: `record_key NVARCHAR(255)` (510) + status
  (40) + datetimeoffset (10) + `id NVARCHAR(255)` (510) = ~1070 bytes, OK (check the actual column types in the
  dialect DDL). Seek with multiple IN ranges returns index order, so no Sort expected.
- oracle: OK; check whether the INLIST ITERATOR keeps a `SORT ORDER BY` (it iterates in list order, which may
  differ from index order).
- jpa entity: `Index(name = "idx_outbox_record_record_key_status_created", columnList = "recordKey, status, createdAt, id")`,
  replacing `idx_outbox_record_record_key_created`.
- mongo: `{recordKey: 1, status: 1, createdAt: 1, _id: 1}` replacing `record_key_created_idx`: the `$in` +
  `sort(recordKey, createdAt, _id)` then needs no blocking SORT stage (today `_id` forces one).

Cross-group note (G1): this index cannot replace `record_key_completed_created` for the predecessor anti join as
written (`older.completed_at IS NULL`). If G1 rewrites that predicate to `older.status IN ('NEW', 'FAILED')`
(equivalent under the same status/completed_at invariant used in H11), this one index could serve both queries
(`record_key = ? AND status = ANY('{NEW,FAILED}') AND created_at < ?`) and `record_key_completed_created`
could be dropped too. Worth testing in Phase 2 if G1 does not pick a partial index.

## Experiment plan

Scenarios A, B, C, D (E ≈ A, optional). Container `outbox-db-H10`.
1. `./start-postgres.sh H10 <S> --ddl ../experiments/H10/indexes-replace.sql` (pre-seed, grows like production).
2. `./run-explain.sh -c outbox-db-H10 -s H10-<S> findIncompleteRecordPages findIncompleteRecordsByRecordKey`,
   then the same with `--generic`, and with `--costs -r 1`.
3. D only: repeat step 2 with `--set random_page_cost=1.1 --tag rpc11` and with
   `--set enable_bitmapscan=off --tag nobitmap` (records the runtime of the ordered path even if not chosen).
4. Equivalence: `./compare-results.sh outbox-db-H10 queries/findIncompleteRecordPages.sql queries/findIncompleteRecordPages.sql --var keys=<n>`
   is trivially PASS; instead run the baseline file on a baseline container and on H10 and diff the outputs
   (or simply note "SQL unchanged"). Also run on the edge seed (`start-postgres.sh H10e edge`).
5. Repeat step 2 for `indexes.sql` (add only) on B and D, and `indexes-noid.sql` on B and D.
6. Regression (separate step, sequential): `./run-suite.sh H10-<S> <S> --ddl ../experiments/H10/indexes-replace.sql`
   for A, B, D; check `deleteByRecordKeyAndStatus`, the key queries (they used `record_key_created` 16x in D) and
   write cost / index size.

## Results (per scenario): plan shape, indexes used, median ms, buffers, before → after

Method (G2 iteration 1, drivers in `experiments/G2-iter1/`): one container per scenario, seeded once; control
measured first, then index states applied **post-seed in place** (+ `VACUUM ANALYZE`), so before/after share the
same heap. 1 warm-up + 3 runs, median; `shared hit` in parentheses; `spill` = external sort. Labels:
`results/G2ctl-<S>` (before), `results/H10-<S>` (replace variant), `H10add-<S>`, `H10noid-<S>`.
Indexes: rksc = `record_key_status_created` (new), rkcc = `record_key_completed_created`, rkc = `record_key_created`.

### H10 replace (`indexes-replace.sql`), default settings
| S | query | mode | before ms (hit) | after ms (hit) | Δ | after plan [indexes] |
|---|---|---|---|---|---|---|
| A | pages 10 | custom | 0.32 (42) | 0.05 (60) | -83% | Index Scan [rksc] |
| A | pages 100 | custom | 0.86 (335) | 0.30 (604) | -66% | Index Scan [rksc] |
| A | pages 1000 | custom | 4.21 (510) | 4.14 (510) | -2% | Sort(Index Scan) [status] |
| A | byKey | custom | 0.29 (13) | 0.03 (6) | -90% | Index Scan [rksc] |
| A | pages 10 | generic | 0.14 (65) | 0.06 (60) | -55% | Index Scan [rksc] |
| A | pages 100 | generic | 0.89 (335) | 0.68 (332) | -23% | Sort(BHS(BIS)) [rksc] |
| A | pages 1000 | generic | 11.32 (3293) | 3.85 (6021) | -66% | Index Scan [rksc] |
| A | byKey | generic | 0.05 (23) | 0.04 (6) | -22% | Index Scan [rksc] |
| B | pages 10 | custom | 0.18 (68) | 0.06 (110) | -64% | Index Scan [rksc] |
| B | pages 100 | custom | 6.53 (531) | 1.32 (392) | -80% | Sort(BHS(BIS)) [rksc] |
| B | pages 1000 | custom | 22.49 (3864) | 15.69 (3943) | -30% | GM(Sort(P.BHS(BIS))) [rksc] |
| B | byKey | custom | 0.05 (23) | 0.03 (11) | -37% | Index Scan [rksc] |
| B | pages 10 | generic | 0.21 (68) | 0.10 (110) | -53% | Index Scan [rksc] |
| B | pages 100 | generic | 6.62 (531) | 1.35 (392) | -80% | Sort(BHS(BIS)) [rksc] |
| B | pages 1000 | generic | 21.94 (3864) | 5.88 (11199) | -73% | Index Scan [rksc] |
| B | byKey | generic | 0.05 (23) | 0.03 (11) | -43% | Index Scan [rksc] |
| D | pages 10 | custom | 3.96 (464) | 2.86 (400) | -28% | Sort(BHS(BIS)) [rksc] |
| D | pages 100 | custom | 39.87 (1858 spill) | 40.47 (1858 spill) | +1% | Sort(Index Scan) [status] |
| D | pages 1000 | custom | 39.82 (1858 spill) | 40.34 (1858 spill) | +1% | Sort(Index Scan) [status] |
| D | byKey | custom | 1.09 (227) | 0.08 (205) | -93% | Index Scan [rksc] |
| D | pages 10 | generic | 6.33 (536) | 6.12 (472) | -3% | GM(Sort(P.BHS(BIS))) [rksc] |
| D | pages 100 | generic | 44.33 (1904 spill) | 43.92 (1904 spill) | -1% | GM(Sort(P.Index Scan)) [status] |
| D | pages 1000 | generic | 44.43 (1904 spill) | 44.28 (1904 spill) | -0% | GM(Sort(P.Index Scan)) [status] |
| D | byKey | generic | 0.78 (1009) | 0.24 (205) | -69% | Sort(BHS(BIS)) [rksc] |

C (no COMPLETED rows): custom unchanged (pages 1000: 11.8 → 12.0 ms, planner keeps parallel bitmap on rkcc + sort),
generic pages 1000 13.5 → 3.8 ms (ordered Index Scan). E ≈ A (pages 100 custom 0.83 → 0.28; 1000 custom 4.15 → 4.28,
generic 11.2 → 3.7).

### H10 add-only (`indexes.sql`): same plans and times as replace (findIncompleteRecordPages)
| S | query | mode | before ms (hit) | after ms (hit) | Δ | after plan [indexes] |
|---|---|---|---|---|---|---|
| A | pages 10 | custom | 0.32 (42) | 0.06 (60) | -83% | Index Scan [rksc] |
| A | pages 100 | custom | 0.86 (335) | 0.34 (604) | -60% | Index Scan [rksc] |
| A | pages 1000 | custom | 4.21 (510) | 4.08 (510) | -3% | Sort(Index Scan) [status] |
| A | pages 10 | generic | 0.14 (65) | 0.06 (60) | -59% | Index Scan [rksc] |
| A | pages 100 | generic | 0.89 (335) | 0.85 (332) | -4% | Sort(BHS(BIS)) [rksc] |
| A | pages 1000 | generic | 11.32 (3293) | 4.01 (6021) | -65% | Index Scan [rksc] |
| B | pages 10 | custom | 0.18 (68) | 0.07 (110) | -63% | Index Scan [rksc] |
| B | pages 100 | custom | 6.53 (531) | 1.19 (392) | -82% | Sort(BHS(BIS)) [rksc] |
| B | pages 1000 | custom | 22.49 (3864) | 14.92 (3943) | -34% | GM(Sort(P.BHS(BIS))) [rksc] |
| B | pages 10 | generic | 0.21 (68) | 0.07 (110) | -69% | Index Scan [rksc] |
| B | pages 100 | generic | 6.62 (531) | 1.28 (392) | -81% | Sort(BHS(BIS)) [rksc] |
| B | pages 1000 | generic | 21.94 (3864) | 5.48 (11199) | -75% | Index Scan [rksc] |
| D | pages 10 | custom | 3.96 (464) | 2.82 (400) | -29% | Sort(BHS(BIS)) [rksc] |
| D | pages 100 | custom | 39.87 (1858 spill) | 40.64 (1858 spill) | +2% | Sort(Index Scan) [status] |
| D | pages 1000 | custom | 39.82 (1858 spill) | 40.31 (1858 spill) | +1% | Sort(Index Scan) [status] |
| D | pages 10 | generic | 6.33 (536) | 6.20 (472) | -2% | GM(Sort(P.BHS(BIS))) [rksc] |
| D | pages 100 | generic | 44.33 (1904 spill) | 43.36 (1904 spill) | -2% | GM(Sort(P.Index Scan)) [status] |
| D | pages 1000 | generic | 44.43 (1904 spill) | 44.51 (1904 spill) | +0% | GM(Sort(P.Index Scan)) [status] |

### H10 noid (`indexes-noid.sql`, B and D): Incremental Sort on (record_key, created_at), otherwise as replace
| S | query | mode | before ms (hit) | after ms (hit) | Δ | after plan [indexes] |
|---|---|---|---|---|---|---|
| B | pages 10 | custom | 0.18 (68) | 0.16 (46) | -12% | Sort(BHS(BIS)) [rksc] |
| B | pages 100 | custom | 6.53 (531) | 1.27 (384) | -81% | Sort(BHS(BIS)) [rksc] |
| B | pages 1000 | custom | 22.49 (3864) | 15.13 (3607) | -33% | GM(Sort(P.BHS(BIS))) [rksc] |
| B | byKey | custom | 0.05 (23) | 0.04 (11) | -17% | IncSort(Index Scan) [rksc] |
| B | pages 10 | generic | 0.21 (68) | 0.21 (46) | +1% | Sort(BHS(BIS)) [rksc] |
| B | pages 100 | generic | 6.62 (531) | 1.28 (384) | -81% | Sort(BHS(BIS)) [rksc] |
| B | pages 1000 | generic | 21.94 (3864) | 7.45 (10863) | -66% | IncSort(Index Scan) [rksc] |
| B | byKey | generic | 0.05 (23) | 0.04 (11) | -22% | IncSort(Index Scan) [rksc] |
| D | pages 10 | custom | 3.96 (464) | 2.92 (389) | -26% | Sort(BHS(BIS)) [rksc] |
| D | pages 100 | custom | 39.87 (1858 spill) | 40.15 (1858 spill) | +1% | Sort(Index Scan) [status] |
| D | pages 1000 | custom | 39.82 (1858 spill) | 40.53 (1858 spill) | +2% | Sort(Index Scan) [status] |
| D | byKey | custom | 1.09 (227) | 0.18 (204) | -83% | IncSort(Index Scan) [rksc] |
| D | pages 10 | generic | 6.33 (536) | 6.22 (461) | -2% | GM(Sort(P.BHS(BIS))) [rksc] |
| D | pages 100 | generic | 44.33 (1904 spill) | 44.30 (1904 spill) | -0% | GM(Sort(P.Index Scan)) [status] |
| D | pages 1000 | generic | 44.43 (1904 spill) | 43.91 (1904 spill) | -1% | GM(Sort(P.Index Scan)) [status] |
| D | byKey | generic | 0.78 (1009) | 0.29 (204) | -62% | Sort(BHS(BIS)) [rksc] |

### Ordered path when the planner is nudged (diagnostic only; not the default)
`random_page_cost=1.1` (rpc11) / `enable_bitmapscan=off` (nobitmap), custom plan, findIncompleteRecordPages,
before (same setting, baseline indexes) → H10 replace:

| S | keys | rpc11 before → after | nobitmap before → after |
|---|---|---|---|
| A | 100 | 0.87 → 0.28 (Index Scan) | 1.18 → 0.34 (Index Scan) |
| A | 1000 | 4.31 → 4.05 (status idx + Sort, unchanged) | 4.09 → 4.01 (unchanged) |
| B | 100 | 6.60 → 0.46 (Index Scan) | 0.85 → 0.46 |
| B | 1000 | 24.2 spill → **4.83** (Index Scan, 11.2k hits, no sort) | 32.3 → **5.30** |
| D | 100/1000 | 40.1 spill → **6.45 / 5.91** (Index Scan, 19.6k hits, no sort) | 41.3 → 40.7 (planner takes status idx + Sort) |

Generic + rpc11 in D: 31.9 → 11.2 / 6.2 ms. H10add with rpc11 in D: 6.1 / 6.4 ms. H10noid with rpc11 in D: 11.0 ms
(IncSort), generic 19.5 ms. Pre-seed-built index on B (`results/G2-wc-B/`, `custom-H10`): 0.07 / 1.25 / 15.2 ms,
same plans as post-seed; with nobitmap 0.06 / 0.40 / 4.79 ms.

Summary: with default settings H10 fixes small key sets (A/E pages 10/100 −65…−85 %, B pages 10/100 −64/−80 %),
all `findIncompleteRecordsByRecordKey` cases (−35…−93 %, plain Index Scan) and every generic plan (B pages 1000
21.9 → 5.9 ms, A 11.3 → 3.9). It does **not** fix the large custom cases: B pages 1000 22.5 → 15.7 ms (parallel
bitmap on rksc + Gather Merge sort, −30 %), D pages 100/1000 unchanged at ~40 ms **with the 12 MB spill**
(planner keeps `status` index scan + Sort). The ordered Index Scan exists and runs in 4.8–6.5 ms there, but the
custom-plan cost model rejects it at `random_page_cost=4` (see Experimenter note).

## Equivalence check: PASS + method
SQL unchanged; checked anyway as output identity of the original query under each index state vs the control state
on the same data (`experiments/G2-iter1/eq.sh`, ordered md5 of full rows). A, B, C, D, E × keys 10/100/1000 ×
custom/generic + `findIncompleteRecordsByRecordKey` custom/generic: **PASS** for H10 replace, add-only (all S) and noid
(B, D). Edge seed (key_ties 0 and 1, same 8 cases each): **PASS** for all three variants. Files:
`results/G2-eq-{A,B,C,D,E,edge}.txt`.

## Regression: other queries whose plan changed; write cost; index size
Full read suite (custom, generic, serial; every harness query) on A and B: control `results/G2ctl-reg-<S>` vs
`results/H10add-reg-<S>` and `results/H10-reg-<S>` (same container, in place). Diff with
`experiments/G1-iter1/reg-compare.py` (shape/index change or >10 % and >0.2 ms slower):
- Plan changes caused by the index: only `findIncompleteRecordPages`, `findIncompleteRecordsByRecordKey` and
  `deleteByRecordKeyAndStatus` (A 0.245 → 0.048 ms, BitmapAnd → Index Scan on rksc; B 0.066 → 0.041). All faster.
- Other shape flags: `findRecordKeysInPartitions_filter` / `findOldestDueNextRetryAtByPartitions_filter` (A generic 64p
  57 → 10 ms; B custom 64p 169 → 120 ms) — neither plan uses rksc or rkc; the flips come from the re-sampled
  statistics after `VACUUM ANALYZE` (G1 territory, same plan family as baseline). Not caused by H10.
- Only \">10 % slower\" row: `findOldestCreatedAtByPartitions` A 256p custom 1.28 → 1.70 ms, identical plan and buffers
  (510 hits), control runs 1.62/1.25/1.28 → noise.
- **Replace variant (drop `record_key_created`)**: in the control suites (A, B) and the baseline suites (C, D, E) rkc
  is used only by `findIncompleteRecordPages` (A/E generic 1000, B 1000, D 10), `findIncompleteRecordsByRecordKey` and
  `deleteByRecordKeyAndStatus` (D). With H10 replace each of them moves to rksc and is faster; no query loses its plan.
  (D `deleteByRecordKeyAndStatus` under H10 not executed; same predicate shape as `findIncompleteRecordsByRecordKey`, which
  went 1.09 → 0.08 ms.)

Write cost (`write-cost.sh`, fresh container per set, DDL **pre-seed**, `results/G2-wc-<S>/{base,H10,H10add}.txt`):

| set | S | insert ms/1k | update ms/1k | insert WAL B/op | update WAL B/op | index pages (8 kB) |
|---|---|---|---|---|---|---|
| base | A | 28.2 | 34.9 | 2450 | 3308 | 40,368 |
| H10 replace | A | 26.5 | 35.6 | 2520 (+2.9 %) | 3454 (+4.4 %) | 49,994 (+75 MB) |
| H10 add-only | A | 26.8 | 37.0 | 2608 (+6.4 %) | 3586 (+8.4 %) | 57,102 (+131 MB) |
| base | B | 26.6 | 35.6 | 2431 | 3324 | 39,706 |
| H10 replace | B | 26.2 | 37.6 | 2495 (+2.6 %) | 3462 (+4.2 %) | 49,191 (+74 MB) |
| H10 add-only | B | 27.4 | 38.2 | 2610 (+7.4 %) | 3600 (+8.3 %) | 56,299 (+130 MB) |

ms/1k is within the ±3–5 % run noise (base A insert 28.2 vs 25.9 in G1-wc); WAL/op is the stable signal.
Index size: rksc 89–91 MB (post-seed build; ~130 MB grown incrementally) vs 56 MB for rkc; noid variant 46–47 MB.

## Experimenter note
H10 is equivalent and cheap in the replace form (WAL +3–4 %, net +75 MB at 1M rows, no query loses a plan), and it
makes every generic plan and every small key set an ordered Index Scan without Sort. It misses the two cases the
dossier cared most about: custom plans for B 1000 keys (−30 % only, parallel bitmap + sort) and D (unchanged,
still spilling). The forced runs show the index itself would deliver 4.8–6.5 ms there; the planner rejects it
because (1) it prices ~11–20k uncorrelated heap fetches at `random_page_cost=4`, and (2) row estimates for the
key list are far too low: the planner assumes requested keys have the average share of NEW rows, but the key
query only ever hands over keys that do have NEW rows (D: 42 rows estimated vs 19,000 actual on the BitmapAnd), so
the external sort is never priced. `random_page_cost=1.1` (common on SSD) flips D and B-1000 to the ordered path.
H10noid is ~2× slower than H10 on the ordered path (IncSort), so keep `id`. Same planner limit applies to H12.


## Orchestrator decision
rejected (superseded). Lon on existing index gives most of the gain at zero DDL (ledger: `../STATE.md`; final report: `../99-final-report.md`)
