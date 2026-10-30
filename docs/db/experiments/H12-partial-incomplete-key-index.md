# H12: Reuse G1's partial index `(record_key, created_at, id) WHERE completed_at IS NULL` (+ H11 predicate)
Status: measured
Group: G2 (iteration 1). Depends on: H11 rewrite. Shares its index with G1 (key-selection anti join).
Target queries: `findIncompleteRecordPages` (hot), `findIncompleteRecordsByRecordKey` (SPI).

## Change

```sql
-- H12/indexes.sql
CREATE INDEX idx_outbox_record_incomplete_key_created
    ON outbox_record (record_key, created_at, id) WHERE completed_at IS NULL;
```
Queries: the H11 rewrite (`H12/queries/` = copy of `H11/queries/`), i.e. `... AND status = :status AND completed_at IS NULL
ORDER BY record_key, created_at, id`. Without the extra predicate the planner cannot prove the partial-index
predicate from `status = 'NEW'` and will not use the index.

If G1's accepted index has a different column list, re-target this hypothesis to it: any partial index
`WHERE completed_at IS NULL` whose leading columns are `(record_key, created_at[, id])` gives the ordered scan below.
A `(partition_no, record_key, created_at, id)` variant would NOT give order for this query (partition_no is not in
the predicate); in that case H10 or H11 is the G2 answer.

Variant H12-alt (`H12/indexes-status-new.sql`): `(record_key, created_at, id) WHERE status = 'NEW'` with the ORIGINAL
query (no rewrite). It matches in custom plans only: the application binds `status` as a parameter, so a generic
plan (`status = $n`) cannot prove the predicate and falls back to today's plans. Measure it to quantify that risk;
it is not recommended unless the SQL inlines `status = 'NEW'` as a literal.

## Rationale / expected plan

The partial index holds only incomplete rows (A ~6.2k entries, B ~200k, D ~20k; well under 1/10 of the table),
so a probe per key touches only that key's NEW/FAILED rows, already in `(created_at, id)` order. With the SAOP on
the leading column the Index Scan returns `(record_key, created_at, id)` order:

```
Index Scan using idx_outbox_record_incomplete_key_created on outbox_record
  Index Cond: ((record_key)::text = ANY ('{...}'::text[]))
  Filter: ((status)::text = 'NEW'::text)        -- removes the rare FAILED rows only
```
No Sort, no BitmapAnd. Generic-plan safe (`completed_at IS NULL` is literal in the SQL).
Same D caveat as H10: random heap fetches may make the planner choose bitmap + Sort for 19k rows; measure with
`random_page_cost=1.1` and `enable_bitmapscan=off`.

Expected (custom, ms, 10/100/1000 keys): A ~0.1/0.3/2; B ~0.1/0.6/5; C ~0.1/0.5/4; D ~1.5/8–12/8–12 (ordered
path) or ~2/30/30 (bitmap + spill). Write cost: completed rows leave the index on their final update; index
small (A ~0.5 MB, B ~15 MB) — near-zero incremental cost when it is already added for G1, small otherwise.

## Semantic equivalence

Same as H11 (rows with `status = 'NEW'` always have `completed_at IS NULL`; library invariant, not a schema
constraint). Order is given by the explicit ORDER BY; the index only removes the sort.
Check: `compare-results.sh` original vs `H12/queries/*` on A–E (keys 10/100/1000, custom and `--generic`) and the
edge seed (ties on `created_at`, FAILED heads, not-due NEW).

## Portability

- postgres: partial index fine.
- sqlserver: filtered index `WHERE completed_at IS NULL` OK (literal predicate, so parameterised queries can use it);
  the query must contain `completed_at IS NULL` literally (it does after H11).
- mysql / mariadb: no partial indexes. Fallback: H11 with the existing `record_key_completed_created`. In InnoDB that
  index is effectively `(record_key, completed_at, created_at, id)` (implicit PK suffix) and `completed_at IS NULL`
  is an equality-like `ref` condition, so the ORDER BY may be satisfied without a filesort. Check in Phase 3.
- oracle: no partial indexes; emulation via function-based index on
  `CASE WHEN completed_at IS NULL THEN record_key END` etc. would require the query to use the same expressions —
  not worth it. Fallback = H11 with `record_key_completed_created`.
- jpa entity: `@Table(indexes)` cannot express a partial index; it would live in the Flyway/Liquibase/DDL scripts
  only (as G1 must handle anyway). The JPQL gets `and o.completedAt is null`.
- mongo: a partial index `{recordKey: 1, createdAt: 1, _id: 1}` with `partialFilterExpression: {completedAt: null}`
  depends on the server accepting null equality in partial filters (verify on the test version; historically not
  supported) and on how Spring Data stores null fields (omitted by default). Prefer the H10 shape for Mongo.

## Experiment plan

Scenarios A, B, C, D. Container `outbox-db-H12`.
1. `./start-postgres.sh H12 <S> --ddl ../experiments/H12/indexes.sql` (if G1's index is accepted first, use G1's DDL file).
2. `./run-explain.sh -c outbox-db-H12 -s H12-<S> -q ../experiments/H12/queries`, plus `--generic`, `--costs -r 1`;
   D also `--set random_page_cost=1.1 --tag rpc11` and `--set enable_bitmapscan=off --tag nobitmap`.
3. Equivalence: as H11 (compare-results.sh, keys 10/100/1000, custom + generic, edge seed).
4. H12-alt on B and D: `start-postgres.sh H12a <S> --ddl ../experiments/H12/indexes-status-new.sql`, run the ORIGINAL
   queries (`run-explain.sh` without `-q`) custom and `--generic`; expect the generic plan not to use the index.
5. Regression (only if G1 does not already cover this index): `./run-suite.sh H12-<S> <S> --ddl ../experiments/H12/indexes.sql -q <dir>`
   where `<dir>` is a copy of `harness/queries/` with `H12/queries/*.sql` copied over it (the suite runs every file
   in the `-q` directory; files missing there are skipped).

## Results (per scenario): plan shape, indexes used, median ms, buffers, before → after

Method: `experiments/G2-iter1/run-scenario.sh`; index states applied **post-seed in place** on the control container.
Index = G1's H02 index `idx_outbox_record_incomplete_key (record_key, created_at, id) WHERE completed_at IS NULL`
(`experiments/H02/indexes.sql`, identical to `H12/indexes.sql` except the name) — label `results/H12-<S>`; then
the full H04 set (H02 index + partial `(created_at, id) INCLUDE (...)`, `experiments/H04/indexes.sql`) — label
`results/H12h04-<S>`. Queries = H11 rewrite (`H12/queries/`). Before = `results/G2ctl-<S>` (original SQL, baseline
indexes). inc_key = the partial index.

### H12 with the H02 index
| S | query | mode | before ms (hit) | after ms (hit) | Δ | after plan [indexes] |
|---|---|---|---|---|---|---|
| A | pages 10 | custom | 0.32 (42) | 0.05 (48) | -84% | Index Scan [inc_key] |
| A | pages 100 | custom | 0.86 (335) | 0.24 (454) | -72% | Index Scan [inc_key] |
| A | pages 1000 | custom | 4.21 (510) | 4.45 (326) | +6% | Sort(BHS(BitmapAnd(BIS,BIS))) [status,inc_key] |
| A | byKey | custom | 0.29 (13) | 0.03 (6) | -91% | Index Scan [inc_key] |
| A | pages 10 | generic | 0.14 (65) | 0.07 (48) | -45% | Index Scan [inc_key] |
| A | pages 100 | generic | 0.89 (335) | 0.27 (454) | -70% | Index Scan [inc_key] |
| A | pages 1000 | generic | 11.32 (3293) | 1.97 (3166) | -83% | Index Scan [inc_key] |
| A | byKey | generic | 0.05 (23) | 0.03 (6) | -37% | Index Scan [inc_key] |
| B | pages 10 | custom | 0.18 (68) | 0.17 (50) | -6% | Sort(BHS(BIS)) [inc_key] |
| B | pages 100 | custom | 6.53 (531) | 1.21 (399) | -81% | Sort(BHS(BIS)) [inc_key] |
| B | pages 1000 | custom | 22.49 (3864) | 24.00 (2074 spill) | +7% | Sort(BHS(BitmapAnd(BIS,BIS))) [status,inc_key] |
| B | byKey | custom | 0.05 (23) | 0.03 (12) | -39% | Index Scan [inc_key] |
| B | pages 10 | generic | 0.21 (68) | 0.10 (122) | -53% | Index Scan [inc_key] |
| B | pages 100 | generic | 6.62 (531) | 1.26 (399) | -81% | Sort(BHS(BIS)) [inc_key] |
| B | pages 1000 | generic | 21.94 (3864) | 23.55 (2074 spill) | +7% | Sort(BHS(BitmapAnd(BIS,BIS))) [inc_key,status] |
| B | byKey | generic | 0.05 (23) | 0.03 (12) | -39% | Index Scan [inc_key] |
| D | pages 10 | custom | 3.96 (464) | 3.56 (400) | -10% | Sort(BHS(BitmapAnd(BIS,BIS))) [inc_key,status] |
| D | pages 100 | custom | 39.87 (1858 spill) | 39.80 (1994 spill) | -0% | Sort(BHS(BitmapAnd(BIS,BIS))) [status,inc_key] |
| D | pages 1000 | custom | 39.82 (1858 spill) | 40.61 (1994 spill) | +2% | Sort(BHS(BitmapAnd(BIS,BIS))) [status,inc_key] |
| D | byKey | custom | 1.09 (227) | 0.29 (206) | -74% | Sort(BHS(BIS)) [inc_key] |
| D | pages 10 | generic | 6.33 (536) | 2.94 (384) | -54% | Sort(BHS(BIS)) [inc_key] |
| D | pages 100 | generic | 44.33 (1904 spill) | 40.03 (1994 spill) | -10% | Sort(BHS(BitmapAnd(BIS,BIS))) [inc_key,status] |
| D | pages 1000 | generic | 44.43 (1904 spill) | 40.01 (1994 spill) | -10% | Sort(BHS(BitmapAnd(BIS,BIS))) [inc_key,status] |
| D | byKey | generic | 0.78 (1009) | 0.18 (206) | -76% | Index Scan [inc_key] |

### H12 with the full H04 index set: same plans and times as with H02 alone (the second index is never used here)
| S | query | mode | before ms (hit) | after ms (hit) | Δ | after plan [indexes] |
|---|---|---|---|---|---|---|
| A | pages 10 | custom | 0.32 (42) | 0.05 (48) | -84% | Index Scan [inc_key] |
| A | pages 100 | custom | 0.86 (335) | 0.25 (454) | -70% | Index Scan [inc_key] |
| A | pages 1000 | custom | 4.21 (510) | 4.44 (326) | +6% | Sort(BHS(BitmapAnd(BIS,BIS))) [status,inc_key] |
| A | byKey | custom | 0.29 (13) | 0.03 (6) | -91% | Index Scan [inc_key] |
| A | pages 10 | generic | 0.14 (65) | 0.05 (48) | -63% | Index Scan [inc_key] |
| A | pages 100 | generic | 0.89 (335) | 0.27 (454) | -69% | Index Scan [inc_key] |
| A | pages 1000 | generic | 11.32 (3293) | 2.03 (3166) | -82% | Index Scan [inc_key] |
| A | byKey | generic | 0.05 (23) | 0.03 (6) | -37% | Index Scan [inc_key] |
| B | pages 10 | custom | 0.18 (68) | 0.17 (50) | -7% | Sort(BHS(BIS)) [inc_key] |
| B | pages 100 | custom | 6.53 (531) | 1.18 (399) | -82% | Sort(BHS(BIS)) [inc_key] |
| B | pages 1000 | custom | 22.49 (3864) | 23.84 (2074 spill) | +6% | Sort(BHS(BitmapAnd(BIS,BIS))) [status,inc_key] |
| B | byKey | custom | 0.05 (23) | 0.03 (12) | -35% | Index Scan [inc_key] |
| B | pages 10 | generic | 0.21 (68) | 0.07 (122) | -67% | Index Scan [inc_key] |
| B | pages 100 | generic | 6.62 (531) | 1.29 (399) | -81% | Sort(BHS(BIS)) [inc_key] |
| B | pages 1000 | generic | 21.94 (3864) | 23.90 (2074 spill) | +9% | Sort(BHS(BitmapAnd(BIS,BIS))) [inc_key,status] |
| B | byKey | generic | 0.05 (23) | 0.05 (12) | -15% | Index Scan [inc_key] |
| D | pages 10 | custom | 3.96 (464) | 3.41 (400) | -14% | Sort(BHS(BitmapAnd(BIS,BIS))) [inc_key,status] |
| D | pages 100 | custom | 39.87 (1858 spill) | 40.21 (1994 spill) | +1% | Sort(BHS(BitmapAnd(BIS,BIS))) [status,inc_key] |
| D | pages 1000 | custom | 39.82 (1858 spill) | 40.27 (1994 spill) | +1% | Sort(BHS(BitmapAnd(BIS,BIS))) [status,inc_key] |
| D | byKey | custom | 1.09 (227) | 0.12 (206) | -89% | Index Scan [inc_key] |
| D | pages 10 | generic | 6.33 (536) | 2.93 (384) | -54% | Sort(BHS(BIS)) [inc_key] |
| D | pages 100 | generic | 44.33 (1904 spill) | 39.75 (1978 spill) | -10% | Sort(BHS(BIS)) [inc_key] |
| D | pages 1000 | generic | 44.43 (1904 spill) | 40.26 (1978 spill) | -9% | Sort(BHS(BIS)) [inc_key] |
| D | byKey | generic | 0.78 (1009) | 0.09 (206) | -89% | Index Scan [inc_key] |

C: custom unchanged (pages 1000 11.8 → 12.0, parallel bitmap on rkcc + sort), byKey 0.04 → 0.03. E ≈ A
(pages 100 custom 0.83 → 0.30; 1000 custom 4.15 → 4.49, generic 11.2 → 2.1).
The original SQL with the H02 index present (mode `*-origq` in `H12-<S>`) never uses it (no `completed_at` predicate): same
plans as control.

### Ordered path when the planner is nudged (diagnostic only), custom, before → after (same setting)
| S | keys | rpc11 | nobitmap |
|---|---|---|---|
| A | 1000 | 4.31 → 4.51 (BitmapAnd + Sort kept) | 4.09 → **1.84** (Index Scan inc_key) |
| B | 100 | 6.60 → 0.44 (Index Scan) | 0.85 → 0.44 |
| B | 1000 | 24.2 spill → 23.1 spill (BitmapAnd kept) | 32.3 → **4.81** (Index Scan, 9.3k hits) |
| D | 100/1000 | 40.1 spill → 40.4 / 39.5 spill (BitmapAnd kept) | 41.3 → 41.6 (status idx + Sort) |

Generic: D rpc11 31.9 → 6.9 / 6.6 ms and D nobitmap 44.0 → 6.6 / 6.6 ms (Index Scan inc_key, 19.2k hits, no sort);
A generic 1000 is the ordered scan by default (11.3 → 2.0 ms).

### H12-alt (`WHERE status = 'NEW'` partial index, ORIGINAL SQL), B and D (`results/H12alt-<S>`)
| S | query | mode | before ms (hit) | after ms (hit) | Δ | after plan [indexes] |
|---|---|---|---|---|---|---|
| B | pages 10 | custom | 0.18 (68) | 0.06 (110) | -68% | Index Scan [new_key] |
| B | pages 100 | custom | 6.53 (531) | 1.14 (386) | -83% | Sort(BHS(BIS)) [new_key] |
| B | pages 1000 | custom | 22.49 (3864) | 15.54 (1976) | -31% | GM(Sort(P.BHS(BIS))) [new_key] |
| B | byKey | custom | 0.05 (23) | 0.03 (11) | -41% | Index Scan [new_key] |
| B | pages 10 | generic | 0.21 (68) | 0.27 (70) | +29% | Sort(BHS(BIS)) [rkcc] |
| B | pages 100 | generic | 6.62 (531) | 6.71 (531) | +1% | Sort(BHS(BitmapAnd(BIS,BIS))) [rkcc,status] |
| B | pages 1000 | generic | 21.94 (3864) | 21.84 (3864) | -0% | GM(Sort(P.BHS(BitmapAnd(BIS,BIS)))) [status,rkc] |
| B | byKey | generic | 0.05 (23) | 0.05 (23) | -13% | IncSort(Index Scan) [rkc] |
| D | pages 10 | custom | 3.96 (464) | 2.75 (383) | -30% | Sort(BHS(BIS)) [new_key] |
| D | pages 100 | custom | 39.87 (1858 spill) | 40.45 (1858 spill) | +1% | Sort(Index Scan) [status] |
| D | pages 1000 | custom | 39.82 (1858 spill) | 40.24 (1858 spill) | +1% | Sort(Index Scan) [status] |
| D | byKey | custom | 1.09 (227) | 0.08 (205) | -93% | Index Scan [new_key] |
| D | pages 10 | generic | 6.33 (536) | 6.56 (536) | +4% | GM(Sort(P.BHS(BitmapAnd(BIS,BIS)))) [rkc,status] |
| D | pages 100 | generic | 44.33 (1904 spill) | 43.92 (1904 spill) | -1% | GM(Sort(P.Index Scan)) [status] |
| D | pages 1000 | generic | 44.43 (1904 spill) | 44.47 (1904 spill) | +0% | GM(Sort(P.Index Scan)) [status] |
| D | byKey | generic | 0.78 (1009) | 0.92 (1009) | +18% | Sort(BHS(BIS)) [rkc] |
As predicted: custom plans use it (B 100 6.5 → 1.1, 1000 22.5 → 15.5), **generic plans cannot** (`status = $n`), so they
fall back to baseline plans (B generic 100 6.7, 1000 21.8; D generic unchanged ~44 ms). Not recommended.

Summary (default settings): small/medium key lists and byKey are fixed (A/E pages 10/100 −70…−84 %, B pages 100
6.5 → 1.2 ms −81 %, byKey −37…−91 %, ordered Index Scan without Sort where chosen); generic plans in A/E are ordered
(1000 keys −82 %). Large custom lists are **not**: A pages 1000 4.2 → 4.5 (+6 %), **B pages 1000 22.5 → 24.0 (+7 %,
now spilling)**, B generic 1000 21.9 → 23.6, D unchanged ~40 ms with spill. The planner builds BitmapAnd(`status`,
inc_key) and a full Sort because of the row misestimate (D: 42 estimated vs 19,000 actual); the ordered scan it
rejects runs in 1.8 ms (A), 4.8 ms (B) and 6.6 ms (D).

## Equivalence check: PASS + method
`experiments/G2-iter1/eq.sh` (ordered md5 of full rows, rewritten vs original-on-control). A, B, C, D, E × keys
10/100/1000 × custom/generic + byKey custom/generic, with the H02 index (40/40 PASS) and with the H04 set (40/40
PASS); edge seed key_ties 0/1 (16 + 16 PASS); H12-alt on B, D, edge (PASS). The negative control of H11 applies
equally (FAIL when a NEW row has `completed_at` set). Files: `results/G2-eq-{A,B,C,D,E,edge}.txt`.

## Regression
Index identical to G1's H02 (and part of H04): regression and write cost already measured there
(`experiments/H02-…`, `results/H02-reg-{A,B}`, `results/G1-wc-<S>/H02.txt`: insert WAL +6–7 %, ~18 MB in B; H04 set
insert WAL +13–14 %). No additional full-suite run. Size here (post-seed build): A 0.5 MB, B 16 MB, D 1.7 MB (H04
`incomplete_created`: 0.6 / 20 / 2.0 MB).

## Experimenter note
If G1 adopts the H02/H04 index, H12 costs only the H11 predicate and is the best small-list option (A/E/B ≤ 100
keys: ordered Index Scan, 0.05–1.2 ms). With default costs it is still not a fix for large lists: B 1000 keys is
slightly worse than today (+7 %, sort spill, because the planner ANDs with the 200k-entry `status` bitmap) and D is
unchanged. The ordered scan on the partial index is the fastest measured path in every scenario when chosen
(A 1.8, B 4.8, D 6.6 ms for 1000 keys), so the remaining problem is plan choice, not the index: candidates for iteration 2
are dropping `status = :status` from the SQL when `completed_at IS NULL` is present (removes the `status` bitmap option
and the independence misestimate; FAILED rows would then need filtering in Kotlin or a `status <> 'FAILED'` filter),
or extended statistics on `(record_key, status)` / `(record_key, completed_at)`. Using H04's full set changes nothing for G2.


## Orchestrator decision
measured → refine (iter2). planner rejects ordered path at 1000 keys (est 42 vs 19k rows) (ledger: `../STATE.md`; final report: `../99-final-report.md`)

(Final: superseded by H14 Lon on the existing index.)
