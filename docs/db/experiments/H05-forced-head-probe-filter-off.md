# H05: Forced per-key probe for the filter-off key query (fixes the H04 D regression)
Status: measured (S rejected, F insufficient, X works)
Target queries: `findRecordKeysInPartitions` filter off (primary); filter on and `findOldestDueNextRetryAtByPartitions`
filter on are carried unchanged from H04 so the query dir is complete.
Builds on: H04 (indexes and the filter-on / MIN SQL unchanged). Iteration 2 of G1, addresses issue (a).

## Problem (from H04)

D, filter off, custom plans: 4.7 → 14.1 ms (64p), 5.9 → 13.6 ms (256p). Raw:
`results/H04-D/findRecordKeysInPartitions_nofilter-parts64-costs-serial.txt`. The `earlier` side of the anti join
(`completed_at IS NULL AND status = 'NEW' AND next_retry_at <= now`) is estimated at **343 rows, actual 19,000**:
the planner multiplies `P(completed_at IS NULL) ≈ 2%` by `P(status = 'NEW') ≈ 2%` (independence), although NEW ⇒
`completed_at IS NULL`. With 343 rows a `Materialize` over a full scan of `incomplete_created` looks cheaper than 10
index probes, so the join filter runs 190k times. Generic plans (no constants → default selectivities) are healthy
(0.43 ms) and the filter-on query is not affected (its inner side only has `completed_at IS NULL`, estimated correctly).

## Change

Indexes: H04 unchanged (`experiments/H05/indexes.sql` = copy of `H04/indexes.sql`).

**H05-S (primary): correlated scalar head subquery** (`experiments/H05/queries/findRecordKeysInPartitions_nofilter.sql`)

```sql
SELECT o.record_key
FROM outbox_record o
WHERE o.partition_no IN (:partitions)
  AND o.status = :status
  AND o.next_retry_at <= :now
  AND o.completed_at IS NULL
  AND o.id = (
    SELECT e.id FROM outbox_record e
    WHERE e.record_key = o.record_key
      AND e.completed_at IS NULL
      AND e.status = :status
      AND e.next_retry_at <= :now
    ORDER BY e.created_at ASC, e.id ASC
    LIMIT 1
  )
ORDER BY o.created_at ASC, o.id ASC
LIMIT :batchSize
```

**H05-F (variant): optimisation fence** (`.../findRecordKeysInPartitions_nofilter_fence.sql`): the H04 `NOT EXISTS`
text with `OFFSET 0` as the last clause of the subquery. Postgres-only.

**H05-X (diagnostic variant): extended statistics** (`experiments/H05/stats.sql`), H04 SQL unchanged:

```sql
CREATE STATISTICS outbox_record_status_completed_stx (mcv) ON status, completed_at FROM outbox_record;
ANALYZE outbox_record;
```

## Rationale / expected plan

- H05-S: Postgres never pulls up a sublink with `LIMIT` into a join, so the check stays a correlated `SubPlan`
  executed once per outer row that passes the outer filter. Its only sensible plan is a parameterized
  `Limit -> Index Scan using idx_outbox_record_incomplete_key (record_key = o.record_key)` that walks the key's
  incomplete records in `(created_at, id)` order (the index order, no sort) with `status`/`next_retry_at` as filter
  and stops at the first due one. The misestimate can no longer pick `Materialize`, since there is no join to cost.
  Expected D 64p/256p: **~0.1–0.2 ms** (like generic H04: 10 outer hits, ~40–60 buffers). B/C/A/E unchanged vs H04
  (0.11–0.13 ms) ± the SubPlan overhead (one extra heap visit per probe: `e.id` is in the index, `status` and
  `next_retry_at` are not, so the probe is an Index Scan as in H04's healthy plan).
  Plan: `Limit -> Index Only Scan using idx_outbox_record_incomplete_created o (Filter: partition/status/retry; Filter: (id = (SubPlan 1)))`,
  `SubPlan 1 -> Limit -> Index Scan using idx_outbox_record_incomplete_key e`.
- H05-F: `simplify_EXISTS_query` refuses to simplify an EXISTS subquery that has an `OFFSET`, so the sublink is not
  converted into an anti join and stays a correlated SubPlan: per outer row one probe
  `Index Scan using incomplete_key (record_key = $1 AND created_at <= $2)`, stopping at the first row (EXISTS needs
  one). Same expected numbers as H05-S, no ORDER BY needed in the subquery, so it works on any probe index.
- H05-X: the MCV list over `(status, completed_at)` contains the frequent combinations `('NEW', NULL)` (2% in D) and
  `('FAILED', NULL)`; `completed_at` values of COMPLETED rows are unique and fall out of the list. The combined clause
  is then estimated from the MCV (~2% → ~19–20k rows) and the planner should return to probing. Diagnostic: confirms
  the root cause and shows whether a schema-only fix works. Not preferred as the fix: Postgres-only DDL, depends on
  ANALYZE sampling (the `('NEW', NULL)` item can drop out of the MCV when NEW is rare, harmless then because the
  real row count is small too), and it also changes estimates for other queries (needs the full regression suite).

The filter-on query is left in H04's NOT EXISTS form: it is not affected (inner estimate correct), and its anti join
can switch to a hash join for large batches, which the SubPlan forms cannot. Whether to unify both variants on one
form is a style decision for the final recommendation.

## Semantic equivalence argument

H04 filter off (proven equivalent to the original, `H04-...md`): o qualifies iff o is a due `status` row with
`completed_at IS NULL` in the partitions and no row e of the same key with the same predicates precedes it in
`(created_at, id)`. H05-S: the subquery returns the id of the minimum of that set for o's key in the total order
`(created_at, id)` (`id` is the primary key, so the order is total and the minimum unique). o is a member of the set,
so the set is non-empty and `o.id = min.id` ⇔ o is the minimum ⇔ no member precedes o. `ORDER BY e.id` and
`older.id < o.id` use the same column collation, so "precedes" is the same relation as in the NOT EXISTS. Outer
predicates and ORDER BY / LIMIT are identical to H04 → same rows, same order, same prefix for every batch size.
H05-F: identical predicate text; `OFFSET 0` returns all rows, EXISTS only tests non-emptiness. H05-X: SQL unchanged.

## Portability

- postgres: all three. SQL Server: H05-S as `(SELECT TOP 1 e.id ... ORDER BY e.created_at, e.id)` or
  `... OFFSET 0 ROWS FETCH NEXT 1 ROWS ONLY`; mysql/mariadb: `LIMIT 1` in a scalar `=` subquery is allowed (the
  restriction is only for `IN/ALL/ANY/SOME`); oracle 12c+: `FETCH FIRST 1 ROW ONLY` in a correlated scalar subquery
  (verify in Phase 3; older releases rejected correlation inside the inline view Oracle generates for FETCH FIRST).
- H05-F is a Postgres planner fence: MySQL needs `LIMIT` with `OFFSET`, SQL Server needs ORDER BY for OFFSET, so the
  other dialects would keep the plain NOT EXISTS (their optimisers do not share the Postgres misestimate path).
- H05-X: Postgres only (`CREATE STATISTICS`); the schema files would need a Postgres-only statement.
- Outer limit per dialect (unchanged from H04): `LIMIT ?` (postgres, mysql, mariadb), `FETCH FIRST ? ROWS ONLY`
  (postgres, oracle 12c+, mariadb 10.6+), `OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY` or `TOP (?)` (sqlserver).
- jpa: HQL 6 accepts `order by ... limit 1` inside a subquery (rendered per dialect by Hibernate; verify in Phase 3).
  `@Table(indexes)` unchanged. mongo: not applicable (aggregation pipeline; `$sort` + `$group: {$first}` per key).

## Experiment plan

State per scenario (one container, in place): H04 indexes + H04 queries (reference, should reproduce H04 numbers)
→ H05 queries (S and F files) → H05-X (`stats.sql`, run the **H04** query dir, then `DROP STATISTICS
outbox_record_status_completed_stx; ANALYZE outbox_record;`).
1. **D** (the regression), parts 64 and 256: custom-serial, custom, generic-serial, generic, plus `--costs` for
   all three variants. Success: ≤ 1 ms custom, no `Materialize`, inner = probe per outer row. Check the estimate on
   the `earlier` side in H05-X (should be ≥ 10k).
2. A, B, C, E, parts 64/256: custom-serial and generic-serial for the filter-off files (no regression vs H04
   0.11–0.13 ms); batch 100 and 1000 on B and D (the SubPlan form cannot switch to a hash join for big batches; H04
   batch 1000 = 3.3 ms on B).
3. Worst case: `findRecordKeysInPartitions_nofilter@parts=64,partlist_override=999` on B (H04: 15.3 ms, full scan of
   `incomplete_created`; expected unchanged since the SubPlan never runs).
4. Equivalence: `compare-results.sh` original `nofilter` vs H05 `nofilter` and `nofilter_fence` with
   `--var batch=100000000`, A–E × parts 64/256, plus edge seed with and without `key_ties=1`, custom and generic
   (`G1-iter1/eq-check.sh` tie analysis). Small-batch prefix check `batch=10` vs `head -10`.
5. H05-X only: full suite (`run-suite.sh ... --skip-writes`) on A and B to list every plan that changes because of
   the new statistics.

## Results (per scenario): plan shape, indexes used, median ms, buffers, before → after
Method (G1 iteration 2, `experiments/G1-iter2/run-scenario.sh`, tables `results/G1i2-tables.md`, logs `results/logs-iter2/`): one container per
scenario (A, B, C, D, E; `outbox-db-H05..H09`, tmpfs data dir, 1M rows, C 200k), index states applied in place after seeding with
`VACUUM ANALYZE` (fresh VM). Timing: 1 warm-up + 3 runs, median, `custom-serial` = `max_parallel_workers=0` (pgjdbc `withMaxRows`),
`generic-serial` = force_generic_plan + no workers, `--costs` (estimate vs actual), worst case `partlist_override=999`, batch 100/1000 on B, D.
Reference numbers for the iteration-1 states (control, H04) come from `results/G1ctl-<S>` and `results/H04-<S>`; the H04 filter-off file was re-run
on the H04 state (`results/H05ref-<S>`) and reproduces them (D 14.0/14.3 ms, others 0.11-0.16 ms). Numbers: ms (serial custom, parts 64 / 256).

Filter off, ctl -> H04 -> variants (custom-serial, 64p / 256p):

| Scenario | ctl | H04 (NOT EXISTS) | H05-S scalar | H05-F fence (OFFSET 0) | H05-X stats (H04 SQL) |
|---|---|---|---|---|---|
| A | 1.43 / 2.83 | 0.12 / 0.12 | 3.69 / 11.3 | 3.49 / 10.8 | 0.115 / 0.12 |
| B | 55.6 / 73.8 | 0.11 / 0.16 | **151** / 0.13 | 0.12 / 0.12 | 0.116 / 0.127 |
| C | 29.5 / 75.4 | 0.12 / 0.13 | 0.12 / 0.12 | 0.12 / 0.13 | 0.114 / 0.131 |
| D | 4.66 / 5.88 | **14.0 / 14.3** | **392 / 2,369** | **15.5 / 71.8** | **0.085 / 0.10** |
| E | 1.49 / 2.42 | 0.12 / 0.14 | 3.65 / 11.7 | 3.61 / 11.1 | 0.133 / 0.135 |

- **H05-S (scalar head subquery) is worse than the problem it fixes.** The planner estimates the `o.id = (SubPlan)` filter at ~1/ndistinct(id), so on B 64p it
  picks `Bitmap Heap Scan` + 49,184 SubPlan executions + top-N sort (215k buffers, 151 ms; est. rows=49 vs 6,148 actual); on D the SubPlan runs per row of a
  large scan: 392 ms (64p), 2.4 s (256p, 3.9M buffers); on A/E 3.7 / 11 ms. Generic plans are equally erratic (B 64p 155 ms).
- **H05-F (OFFSET 0 fence)**: B/C fine (0.12), but A/E regress (3.5 / 10.8 ms, the planner now probes a worse path) and **D is not fixed (15.5 / 71.8 ms)**.
- **H05-X (`CREATE STATISTICS (mcv) ON status, completed_at`)** with the unchanged H04 SQL: all scenarios 0.085-0.135 ms, D estimate for `earlier` becomes
  correct (plan = Index Only Scan `incomplete_created` + Index Scan probe `incomplete_key`, est. rows 3,945 for the anti join vs 95 before; actual 10 loops).
  Root cause confirmed: independence of `status = 'NEW'` and `completed_at IS NULL`. Generic with H05-X not separately measured (generic H04 was already healthy).
- Worst case (`partlist_override=999`, B): S 0.063 / F 0.055 ms (the planner sees the empty partition), filter on (H04 text) 15.5 ms as in H04.
- Batch 100 / 1000 (B, D): S forms keep their plans (D: 10.7 s per call at 64p batch 100), see `results/H05-{B,D}/*batch*`. Not repeated for X.

## Equivalence check: PASS/FAIL + method
A-E x 64/256, full output (`batch=100000000`) and `head -10` of the original, S and F files vs the original filter-off query: **exact PASS** (A-E: 14/14 per scenario
incl. filter on and MIN). Edge seed (`results/G1i2-edge/equivalence.txt`, key_ties 0/1, custom + generic): exact PASS with `key_ties=0`; with `key_ties=1` only
PASS-TIES (same rows, differences only inside groups of keys with equal `MIN(created_at)`, the original's own order is plan-dependent); no FAIL. H05-X leaves the SQL unchanged.

## Regression: other queries whose plan changed; write cost; index size
SQL-only for S/F. H05-X: statistics only; the full-suite effect of the extended statistics was not measured (only the key queries: no change elsewhere
observed in their plans); `ANALYZE` cost negligible. A Postgres-only `CREATE STATISTICS` statement would have to live outside the shared schema files.

### Experimenter note
H05 does not deliver: the scalar-subquery form is unstable (151 ms B, 392-2,369 ms D, 3.7-11 ms A/E) because the planner underestimates the `o.id = (subquery)` filter,
and the `OFFSET 0` fence leaves D at 15.5 / 71.8 ms and regresses A/E. Only the root-cause fix works: giving the planner a correct estimate (H05-X statistics, or H09's
status-only predicate, which needs no schema object). Use H09's `implied` form or extended statistics instead of a query rewrite; do not adopt H05-S/F.

## Decision + reasoning

_pending_


## Orchestrator decision
X accepted (candidate); S, F rejected. X is PG-only DDL, no write cost; to re-verify on H08a set in Phase 2 (ledger: `../STATE.md`; final report: `../99-final-report.md`)
