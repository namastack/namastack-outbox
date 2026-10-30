# H06: Head-of-key probes on the existing `record_key_completed_created` index (drop the partial key index)
Status: measured (filter on viable, filter-off form rejected)
Target queries: `findRecordKeysInPartitions` (filter on/off), `findOldestDueNextRetryAtByPartitions` (filter on).
Builds on: H04 (outer index + SQL) and H05-S (filter-off form). Iteration 2 of G1, addresses issue (b).

## Change

DDL (`experiments/H06/indexes.sql`): H04 minus `idx_outbox_record_incomplete_key`:

```sql
CREATE INDEX idx_outbox_record_incomplete_created
    ON outbox_record (created_at, id)
    INCLUDE (partition_no, status, next_retry_at, record_key)
    WHERE completed_at IS NULL;
-- no idx_outbox_record_incomplete_key; probes use the existing
-- idx_outbox_record_record_key_completed_created (record_key, completed_at, created_at)
```

SQL (`experiments/H06/queries/`):
- filter on, MIN filter on: **H04 text unchanged** (NOT EXISTS with the H01 bound `older.created_at <= o.created_at`).
- filter off: H05-S scalar head subquery, with the subquery ordered so the existing index delivers the order:

```sql
  AND o.id = (
    SELECT e.id FROM outbox_record e
    WHERE e.record_key = o.record_key
      AND e.completed_at IS NULL
      AND e.status = :status
      AND e.next_retry_at <= :now
    ORDER BY e.completed_at ASC, e.created_at ASC, e.id ASC
    LIMIT 1
  )
```
- filter off, variant: H05-F fence (`findRecordKeysInPartitions_nofilter_fence.sql`), needs no ORDER BY.

## Rationale / expected plan

H04 needs two partial indexes (+13–14% insert time and WAL). The probe index `incomplete_key (record_key, created_at,
id) WHERE completed_at IS NULL` duplicates what `record_key_completed_created (record_key, completed_at, created_at)`
already offers for incomplete rows: in Postgres `completed_at IS NULL` is a btree index condition that nbtree treats
like an equality for positioning (NULLs sort last, so a key's incomplete entries are one contiguous range at the end
of its range), so

- **filter on / MIN on** probe: `Index Scan using idx_outbox_record_record_key_completed_created older
  (Index Cond: record_key = o.record_key AND completed_at IS NULL AND created_at <= o.created_at;
  Filter: created_at < o.created_at OR (created_at = o.created_at AND id < o.id))`. For a head row the range holds
  exactly o itself → one index descent + one heap visit (the tie filter needs `id`, which this index lacks; H04's
  probe was index-only). Non-head rows stop at the first predecessor. Expected ≈ H04 +10–40 buffers per query
  (B 64p: 139 → ~150–180; 0.2 → ~0.25 ms). Index depth: 1M entries (4 levels) vs the partial index's 200k (3 levels).
- **filter off** probe: `SubPlan -> Limit -> Incremental Sort (Presorted Key: completed_at, created_at) -> Index Scan
  using record_key_completed_created (record_key = o.record_key AND completed_at IS NULL; Filter: status, next_retry_at)`.
  `record_key = $param` makes `record_key` constant for pathkeys, so the index order is `(completed_at, created_at)`,
  matching the first two sort keys; Postgres does **not** treat `completed_at IS NULL` as a constant for pathkeys,
  which is why the subquery orders by `completed_at` explicitly (a no-op: every candidate has it NULL). Only `id`
  is sorted, inside groups of equal `created_at` (one row each). Risk: the planner may prefer a plain top-N Sort over
  all incomplete rows of the key when it underestimates them (D: est. ~20 per key, actual ~200 for hot keys):
  then ~200 heap visits per probe, ~2k buffers / ~1–2 ms in D for batch 10 — still better than H04's 14 ms. The fence
  variant avoids the question (no order needed, stops at the first predecessor).

Write cost (b): one partial index fewer than H04 → expected insert +6–8% instead of +13–14% (H04's two partial indexes
split roughly by tuple width: `incomplete_created` ~80 B incl. INCLUDE, `incomplete_key` ~64 B). Update: unchanged vs
control (partial index gets no entry when a record completes).

Interaction with G2: H12 (G2) proposes reusing `incomplete_key` for `findIncompleteRecordPages`; H11 (G2) uses
`record_key_completed_created`. If G2 ends up needing `incomplete_key`, H06 has no saving and H08b applies instead.

## Semantic equivalence argument

SQL of filter on and MIN on is H04's (equivalence proven in iteration 1); the index change cannot change results.
Filter off: H05-S argument; the extra leading sort key `e.completed_at` is NULL for every row admitted by
`e.completed_at IS NULL`, so `(completed_at, created_at, id)` orders that set exactly like `(created_at, id)`.

## Portability

The point of H06 for portability: the probe index exists in **every** dialect and in JPA/Mongo already
(`idx_outbox_record_record_key_completed_created`, `idx_outbox_rec_k_c_c`, JPA `idx_outbox_record_key_completed_created`,
Mongo `{recordKey, completedAt, createdAt}`), so the probe needs no new index anywhere.
- mysql/mariadb (InnoDB): `completed_at IS NULL` is a `ref`-style key part, and the secondary index carries `id`
  (PK) as an implicit trailing column → `ORDER BY completed_at, created_at, id` with `record_key` fixed is fully
  index-ordered (no filesort), NOT EXISTS range `created_at <= ?` also an index range.
- sqlserver: non-unique nonclustered index stores the clustering key `id` as a key column → same full order.
  NULLs sort first in SQL Server, irrelevant here (single NULL group).
- oracle: composite entries with NULL `completed_at` are indexed (trailing `created_at` is NOT NULL), so
  `IS NULL` uses the index; `id` not in the index → small sort on the key's incomplete rows.
- Scalar subquery LIMIT syntax per dialect and JPA: see H05.

## Experiment plan

Same container sequence as H05 (continue in place): H05 state → `DROP INDEX idx_outbox_record_incomplete_key;
VACUUM ANALYZE` → H06 queries.
1. A, B, C, D, E × parts 64/256: custom-serial, custom, generic-serial, generic, `--costs` for the three target
   queries and the fence variant. Compare with H04 (`results/H04-<S>/`) and H05. Watch: probe = `record_key_completed_created`
   with `completed_at IS NULL` **and** `created_at <= ...` in Index Cond (not as Filter); no Hash/Materialize on the inner side;
   filter-off subplan = Incremental Sort (or record whether a full Sort is chosen, D in particular).
2. B and D: batch 100 / 1000 (`findRecordKeysInPartitions_filter@parts=64,batch=1000` etc.).
3. Worst case `partlist_override=999` on B for the three queries (expected = H04, 15 ms: the probe never runs).
4. Equivalence: as H05 (A–E, edge with/without `key_ties=1`, custom + generic, full output and batch prefix).
5. Write cost: `run-writecost.sh`-style fresh container per set, pre-seed DDL, A and B: control, H04, **H06**
   (`--ddl experiments/H06/indexes.sql`); report insert/update ms per 1k and WAL B/op, index sizes.
6. Regression: full suite (`run-suite.sh H06-reg-<S> <S> --ddl ../experiments/H06/indexes.sql`) on A, B, D;
   in particular G2 queries (`findIncompleteRecordPages`, `findIncompleteRecordsByRecordKey`), which lose the
   `incomplete_key` plan they picked up in H04-reg.

## Results (per scenario): plan shape, indexes used, median ms, buffers, before → after
Method (G1 iteration 2, `experiments/G1-iter2/run-scenario.sh`, tables `results/G1i2-tables.md`, logs `results/logs-iter2/`): one container per
scenario (A, B, C, D, E; `outbox-db-H05..H09`, tmpfs data dir, 1M rows, C 200k), index states applied in place after seeding with
`VACUUM ANALYZE` (fresh VM). Timing: 1 warm-up + 3 runs, median, `custom-serial` = `max_parallel_workers=0` (pgjdbc `withMaxRows`),
`generic-serial` = force_generic_plan + no workers, `--costs` (estimate vs actual), worst case `partlist_override=999`, batch 100/1000 on B, D.
Reference numbers for the iteration-1 states (control, H04) come from `results/G1ctl-<S>` and `results/H04-<S>`; the H04 filter-off file was re-run
on the H04 state (`results/H05ref-<S>`) and reproduces them (D 14.0/14.3 ms, others 0.11-0.16 ms). Numbers: ms (serial custom, parts 64 / 256).

Probe on the existing `record_key_completed_created`, `incomplete_created` kept; keys on / MIN on use the H04 NOT EXISTS text (custom-serial, 64p / 256p):

| Query | A | B | C | D | E |
|---|---|---|---|---|---|
| keys on, H04 -> H06 | 0.17/0.26 -> 0.19/0.46 | 0.20/0.36 -> 0.22/0.34 | 0.18/0.29 -> 0.20/0.33 | 0.09/0.09 -> 0.09/0.11 | 0.27/0.61 -> 0.36/0.82 |
| MIN on, H04 -> H06 | 0.13/0.28 -> see `results/H06-A` | 0.17/0.32 -> 0.15/0.34 | (`results/H06-C`) | 0.06/0.09 -> 0.06/0.08 | (`results/H06-E`) |
| keys off (scalar head subquery, ORDER BY completed_at, created_at, id) | 5.5 / 20.1 | **211** / 0.15 | 0.14 / 0.15 | **400 / 2,420** | 5.9 / 20 |
| keys off fence | 4.5 / 15.2 | 0.12 / 0.12 | 0.12 / 0.12 | 16.7 / 81.3 | 4.6 / 15.3 |

- Filter on with the probe on the existing index: same plan family as H04 (`Index Only Scan incomplete_created` + probe on `record_key_completed_created` with
  `completed_at IS NULL AND created_at <= ...` as index conditions), **0.09-0.36 ms at 64p, 0.11-0.82 ms at 256p**, about +10-35% buffers vs the partial key index (B 181 vs 139 at 64p).
  Generic: 0.38-0.66 ms (64p) / 0.75-1.9 ms (256p) vs H04 0.29-0.6 / 0.65-1.5.
- Filter off: the H05-S form plus the extra `completed_at` sort key inherits all of H05-S's instability (see H05); fence same as H05.
- Worst case B: filter on 15.7 ms (same as H04, the probe never runs), others 0.03-0.07 ms.
- Batch 100/1000 (B, D): `results/H06-{B,D}/*batch*`.

## Equivalence check: PASS/FAIL + method
A-E x 64/256, edge seed (key_ties 0/1, custom + generic): exact PASS (14/14 per scenario), PASS-TIES only with `key_ties=1`; no FAIL (`results/H06-<S>/equivalence.txt`, `results/G1i2-edge/`).

## Regression: other queries whose plan changed; write cost; index size
Write cost (fresh containers, pre-seed DDL, A; B in `results/G1i2-wc-{A,B}/`): H06 insert 27.4 ms/1k, WAL 2,591 B/op (+6% vs control 2,438); update 36.5 ms/1k, WAL 3,304 (-0.4%).
H04 for comparison: 27.3 ms, 2,752 B/op (+13%). B: H06 2,579 B/op (+6%) vs H04 2,752. The new index is only `incomplete_created` (A 0.6 MB, B 20 MB).
The G2 queries keep their `record_key_completed_created` plans (H08 regression table).

### Experimenter note
Using the existing key index for the head probe costs 10-35% more buffers than the partial key index but stays within 0.1-0.8 ms and halves the insert-WAL penalty
(+6% vs +13%). Filter on / MIN on are fine; the filter-off form from H05-S must not be used (see H05).

## Decision + reasoning

_pending_


## Orchestrator decision
accepted as component of H08a. filter-off form (scalar) rejected (ledger: `../STATE.md`; final report: `../99-final-report.md`)
