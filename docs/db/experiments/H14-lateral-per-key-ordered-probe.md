# H14: One ordered index range per key (`unnest` keys + LATERAL) instead of IN list + global sort
Status: measured
Target queries: `findIncompleteRecordPages` (G2 hot path). `findIncompleteRecordsByRecordKey` is already a single-key ordered probe (no change).

## Change
Query rewrite, three variants (files under `H14/queries*/`, harness conventions as `harness/queries/findIncompleteRecordPages.sql`, generic mode included):

```sql
-- H14 (queries/): strict order, status only; indexes: H10 (record_key,status,created_at,id), H15 partial, or baseline record_key_created
SELECT o.*
FROM (SELECT t.k FROM unnest(ARRAY[:keys]::varchar[]) AS t(k) ORDER BY t.k) k
CROSS JOIN LATERAL (
  SELECT r.* FROM outbox_record r
  WHERE r.record_key = k.k AND r.status = 'NEW'
  ORDER BY r.created_at, r.id) o
ORDER BY k.k, o.created_at, o.id;
```
- H14n (`queries-noorder/`): same without the top `ORDER BY` (see "Order the consumer relies on").
- H14c (`queries-cat/`): inner adds `AND r.completed_at IS NULL` (keeps `status = 'NEW'`, because FAILED also has NULL
  `completed_at`); for H12/H02 partial or baseline `record_key_completed_created`.
Index states (`H14/indexes-*.sql`): 0 = no DDL (baseline), 1 = H10 index. H15/H16 states are reused (partial status-based, rkcc+id).
Production form of the key list: `FROM (VALUES (:k1),(:k2),...)` (Spring expands a `List<Object[]>` parameter) or `unnest(?::varchar[])`
(PG only); the `ARRAY[$1..$n]` form is the harness stand-in (planner row estimate = n in both).

## Rationale / expected plan
Root cause from iteration 1: one global `IN (1000 keys)` query makes the planner estimate 42 rows (status x completed_at
treated as independent) and choose bitmap + full Sort of wide rows (spills in D, B1000). With LATERAL the only legal join
is a nested loop parameterised on the key, so the plan is `Sort(unnest 1000 text) -> Nested Loop -> Index Scan (record_key = k [AND status = 'NEW'])`
returning rows already in `(created_at, id)` order: no row-level Sort, so no spill at any page size and no dependence on
a row estimate for the whole key set. Per-probe estimates are per key (~8-20 rows), where an ordered Index Scan is always the cheapest path.
Generic plans are safe (probe is parameterised by `k.k`, outer estimate = list length).
Expected (from the ordered-scan runs with `enable_bitmapscan=off`: B1000 4.8, D 6.6 ms): B 1000 22 -> ~5 ms (-75%, target met),
B 100 6.6 -> ~0.6 ms, D 40 -> ~7 ms, A 1000 4.2 -> ~2.5 ms (1000 index probes). Risk: with H14 (top ORDER BY) the planner may add an
Incremental Sort (presorted by `k.k`) or, worse, a full Sort of the join output; check `Sort Method`/`Presorted Key` in EXPLAIN.
State 0 (no DDL, `record_key_created` + Filter `status`) is expected to be fine in A/B (20 rows/key) but reads all 1000 rows/key in D.

## Order the consumer relies on (and whether `ORDER BY record_key` is needed)
`JdbcOutboxRecordRepository.findIncompleteRecordPages` does `.list().filterNotNull().groupBy { it.recordKey }` and builds
`recordKeys.associateWith { LazyOutboxRecordPage(rowsByKey[key].orEmpty(), ...) }`. `groupBy` preserves encounter order inside each
group, and `OutboxProcessingScheduler.processRecordKey` walks `page[index]` in order and stops at the first record not completed.
So the consumer relies on exactly: **for each key, rows in ascending `(created_at, id)`**. Cross-key order and contiguity of a key's
rows are not used (page order for keys comes from the requested `Set`, not the result). Dropping `record_key` from the ORDER BY alone gives
no cheaper plan (`ORDER BY created_at, id` over a key set still needs a global sort); the gain comes only from the LATERAL shape, where the
order falls out of the nested loop (H14n). The main H14 variant keeps the full `ORDER BY` so the output is byte-identical by SQL semantics.
Verification (both variants, A-E, keys 10/100/1000, custom + generic, edge seed with created_at ties):
(1) strict: ordered md5 of the full result vs original (existing `eq.sh`); H14 must be 100% identical, H14n expected identical because the outer
sort uses the same default collation as the `record_key` column (if a deployment's column collation differs, H14n differs in cross-key order only);
(2) consumer-level: `md5(string_agg(id, ',' ORDER BY ord))` per key where `ord` = position within the result, then compare the per-key
digests as a set (order-insensitive across keys). H14n must PASS (2) always and (1) in the test DB. Tie rows `created_at` equal: both orders break ties by `id`, deterministic.

## Semantic equivalence argument
Same rows: `{(r) | r.record_key IN keys AND r.status = 'NEW'}` (join on equality with a duplicate-free key list; the Kotlin set has no
duplicates; `unnest` of a duplicated list would duplicate rows, so keep the set semantics or add DISTINCT in the outer subquery).
H14c adds `completed_at IS NULL`, true for every NEW row (invariant: only `markCompleted` sets `completed_at`, together with COMPLETED; same
invariant as H11/H12). Order: outer sorted by key, inner by `(created_at, id)` = original `ORDER BY record_key, created_at, id`.
`SELECT o.*` returns exactly the `outbox_record` columns in table order, so the existing row mapper is untouched.

## Portability
- postgres: as written; production key source `VALUES`/`unnest(?::varchar[])`.
- mysql 8.0.14+: `JOIN LATERAL (...) ON TRUE`; derived-table `VALUES ROW(?),ROW(?)` needs 8.0.19+, or `SELECT ? AS k UNION ALL ...`. Optimizer still must use the (record_key, ...) index per probe (verify in Phase 3).
- mariadb: no LATERAL (as far as known; verify on mariadb:12 in Phase 3) -> keep the IN form (+ H10 or rkcc+id index).
- sqlserver: `CROSS APPLY (SELECT TOP (100) PERCENT ... ORDER BY ...)` (ORDER BY needs TOP/OFFSET in a derived table) and keep the outer ORDER BY; spill behaviour of the outer sort must be re-measured.
- oracle 12c+: `CROSS APPLY`/`LATERAL`, key rows via `SELECT :1 AS k FROM dual UNION ALL ...`; 1000-key limit already matches the IN-list limit.
- JPA: JPQL has no LATERAL; JPA keeps the IN query (benefits from the index only). Mongo: not applicable (`$in` + compound index).
- Code impact: dialect-specific SQL for the bulk query (the repository currently has one SQL text) -> ADR candidate if adopted.

## Experiment plan (sequential, one container per scenario, states in place + `VACUUM ANALYZE`; keys 10/100/1000)
1. A, B, D, E; custom and generic (`plan_cache_mode=force_generic_plan`); 1 warm-up + 3 runs, median ms and shared hits, `Sort Method` (spill check, D).
2. States: S0 baseline indexes (`queries`, `queries-cat`), S1 H10 (`queries`, `queries-noorder`), S2 H15a partial (`queries`), S3 H02/H12 partial (`queries-cat`). Control = original SQL on the same state.
3. Equivalence (above) incl. edge seed; negative control: H14c without `status = 'NEW'` must FAIL on FAILED-head keys.
4. Planning time (custom, 1000 keys) for VALUES vs unnest forms; no write-cost run needed (no DDL of its own; costs come from H10/H15/H16).

## Results (per scenario): plan shape, indexes used, median ms, buffers, before -> after
Pending.
## Equivalence check: PASS/FAIL + method
Pending.
## Regression: other queries whose plan changed; write cost; index size
Pending (query-only; index effects: see H10, H15).
## Decision + reasoning
Pending.

## Experimenter note (iteration 2, Sonnet experimenter; drivers `G2-iter2/`, raw `results/G2i2-{A,B,D,E}/`, `G2i2-eq-*.txt`)
Index base = H08a for all rows unless stated; control = original IN query on H08a (no regression vs baseline: A/B/D/E within noise, B 22.7 / D 41 ms, D still spills).
Median ms, `pages` keys 10/100/1000, custom plan; generic in brackets for 1000 keys. `*` = sort spill. Baseline (G2ctl) B 0.18/6.53/22.5 (21.9), D 3.96/39.9*/39.8* (44.4*), A 0.32/0.86/4.2 (11.3), E ~ A.

Key finding: **the LATERAL probe only gets a per-key index range scan if the inner predicate contains `completed_at IS NULL`.** With `status = 'NEW'` alone (L, Ln) on H08a the per-key plan is BitmapAnd(record_key idx, status idx) = 148 ms (A 1000 keys), 109 ms (D): much worse than the control. Adding `completed_at IS NULL` (Lc) fixes it; ordering the inner by `completed_at, created_at, id` (Lo; completed_at is constant NULL there so the result order is identical) removes the per-key Sort because the index order (record_key, completed_at, created_at[, id]) then matches.

| scenario | variant (state) | 10 | 100 | 1000 (generic) | plan @1000 |
|---|---|---|---|---|---|
| A | control H08a | 0.30 | 0.95 | 4.30 (11.4) | Sort(Index Scan) |
| A | L / Ln (H08a) | 2.1 | 15 | 148 / 144 (151/146) | NL, BitmapAnd per key - rejected |
| A | Lc / Lcn (H08a) | 0.26/0.20 | 0.70/0.54 | 7.1/5.1 (10.3/5.5) | NL, Sort(Index Scan) per key |
| A | Lo / Lon (H08a) | 0.15/0.12 | 0.71/0.51 | 6.6/4.8 (11.7/5.4) | Incremental Sort(Index Scan) |
| A | Lo / Lon (U2b) | 0.13/0.09 | 0.69/0.39 | 6.0/4.0 (7.0/4.4) | Index Scan, no per-key sort (Lon) |
| B | baseline / control H08a | 0.18 | 6.53 / 6.50 | 22.5 / 22.7 (21.9/22.4) | Gather Merge(Sort(Parallel BHS(BitmapAnd))) |
| B | Lc / Lcn (H08a) | 0.32/0.22 | 1.19/0.80 | 12.1/8.3 (17.0/8.6) | |
| B | **Lo / Lon (H08a)** | 0.21/0.14 | 1.23/0.76 | 11.6/**7.9** (17.5/8.6) | Lo -49%, Lon -65% at 1000 |
| B | **Lo / Lon (U2b)** | 0.24/0.12 | 1.28/0.63 | 10.6/**7.1** (13.9/6.9) | Lo -53%, Lon -68% at 1000 |
| D | control H08a | 4.0 | 40.8* | 41.2* (44.7*) | Sort(Index Scan), spill |
| D | Lc / Lcn (H08a) | 4.2/2.3 | 37.6/20.8 | 37.5/20.8 (36.8/21.1), no spill | per key BHS(BIS), filter status, 1000 rows/key read |
| D | Lo / Lon (H08a) | 4.2/2.4 | 38/22 | 38/22 (38/21.5), no spill | same |
| D | **Lo / Lon (U2b)** | 2.9/**0.84** | 26.5/**8.9** | 26.8/**8.8** (27.4/8.9), no spill | Lon: Index Scan per key (-78% vs 40) |
| E | Lo / Lon (U2b) | 0.13/0.09 | 0.68/0.43 | 6.1/4.1 (6.9/4.6) | E ~ A |

Reading: with a top `ORDER BY k.k, o.created_at, o.id` (Lc, Lo) the planner adds an Incremental Sort over the join output; at 1000 keys that costs ~3-4 ms in B and, in the generic plan, picks a worse shape (B generic 17 vs 8.6 ms). Without top ORDER BY (Lcn, Lon) the plan is the pure Sort(unnest) -> Nested Loop -> per-key ordered index scan. Target B -50% at 100 and 1000 keys (custom): met by Lo (H08a) at 100 (-81%) and 1000 (-49%, borderline), by Lon on H08a/U2b at both (-65/-68%) and generic for Lon (-61/-69%); Lo generic at 1000 misses (-20% H08a, -37% U2b). D: no spill in any LATERAL `c/o` variant; D time reaches 8.8 ms only with U2b+Lon (D rows are 1000/key; H08a leaves `status` as a filter on the whole key range).

### H14 item 3: status predicates as query predicates (bulk query)
- **P1** IN form + `AND status <> 'COMPLETED'` and **P2** + `AND status IN ('NEW','FAILED')` (both next to `status = 'NEW'`; result identical because `status = 'NEW'` is the strictest conjunct): P1 = control in every scenario (B 1000 25.8* new spill, A 4.3). P2 is worse: A 10 keys 0.83 vs 0.30, generic A 100/1000 keys 19.5/227.7 ms (vs 0.89/11.4), D generic 88.9 vs 44.7, B 10 keys 5.5 ms. Redundant same-column conjuncts are multiplied as independent selectivities (and `IN` adds a second index-arg), so estimates get worse, not better; the plan stays Bitmap/Sort.
- **LP** LATERAL + `r.status <> 'COMPLETED'`: = L (BitmapAnd per key, A 150 ms, D 108 ms).
- Conclusion: for the bulk load the query must return only NEW; `status = 'NEW'` alone is the one status predicate that is both exact and gives MCV-based estimates (H09). Any extra status conjunct is semantically redundant and measurably harmful or neutral. The status predicate does not help index use here; `completed_at IS NULL` (an index-prefix condition on rkcc/U2b) is what enables the ordered per-key range.

### Equivalence
`G2i2-eq-{A,B,D,E,edge}.txt`, `eq2.sh` (exact ordered md5 vs original on baseline indexes; per-key digest fallback). Keys 10/100/1000, custom + generic, plus `findIncompleteRecordsByRecordKey`; edge seed key_ties 0 and 1. **A-E 138 PASS each, edge 336 PASS, 0 FAIL, 0 PASS-PERKEY**: even the no-top-ORDER BY variants (Ln, Lcn, Lon) return byte-identical ordered output (outer sort by key uses the same collation; ties broken by id), for every state (H08a, U2b, H10). Negative control (`G2i2-eq-negctl.txt`, a NEW row of key edge-101 with completed_at set): Lc, Lo, Lon FAIL-ROWS (884 vs 885 rows) as expected; the control and L also differ only by that changed column. Invariant check: 0 NEW rows with completed_at in all seeds.

### Regression
- Control query on H08a vs baseline: unchanged (above); byKey query: A 0.25 (bitmap) / B 0.05 / D 1.07 ms, same as baseline plans; on U2b same; on H10 0.03-0.08 ms plain Index Scan.
- G1 key queries on U2b (`results/G2i2-{A,B,D}-g1/`, serial): same plans as on H08a (Anti Join with incomplete_created + the key index, Limit early stop); filter on 64/256p custom A 0.22/0.35 -> 0.23/0.36, B 0.21/0.43 -> 0.21/0.38, D 0.10/0.11 -> 0.09/0.10; MIN on/filter A 0.16/0.34 -> 0.16/0.30; generic unchanged (0.3-1.2). Filter-off scalar form (known issue, H05) B 64p 218 -> 177, D 64p 395 -> 14.5, D 256p 2421 -> 77 ms (id in the index makes the per-key probe cheaper).
- Write cost, fresh containers, A, 50k ops (H08a re-measured: 26.6 ms/2,488 B insert, 34.7 ms/3,178 B update): see H16.
- LATERAL form is PG-specific SQL (see Portability); every row above for H14 uses `unnest(ARRAY[...])` key source.


## Orchestrator decision
**Lon accepted (PG)**; L/Ln with status alone rejected (BitmapAnd, A 15/148 ms). relies on NEW ⇒ completed_at NULL (neg. control FAILs); MariaDB/JPA/Mongo keep IN form (ledger: `../STATE.md`; final report: `../99-final-report.md`)
