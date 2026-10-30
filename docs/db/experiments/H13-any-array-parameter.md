# H13: `record_key = ANY(?)` with one array parameter instead of an expanded IN list (Postgres dialect only)
Status: measured
Group: G2 (iteration 1)
Target queries: `findIncompleteRecordPages` (hot).

## Change

```sql
SELECT * FROM outbox_record
WHERE record_key = ANY(:recordKeys) AND status = :status   -- :recordKeys bound as varchar[] (Connection.createArrayOf)
ORDER BY record_key, created_at, id;
```
Harness file: `H13/queries/findIncompleteRecordPages.sql` (custom: literal `ARRAY[...]::varchar[]`; generic:
`PREPARE q(varchar[], varchar)`). If H11/H12 is accepted, add `AND completed_at IS NULL` to this file before measuring
the combination.

## Rationale / expected plan

Spring's `NamedParameterJdbcTemplate` expands `IN (:recordKeys)` to one `?` per key, so the SQL text differs for
every key count (1..1000). Consequences: up to 1000 distinct statements in pgjdbc's per-connection cache
(`preparedStatementCacheQueries=256`), each needing 5 executions before it is server-prepared, and planning cost
that grows with the list (1000 parameters). With `= ANY(?)` there is one statement text; pgjdbc server-prepares it
after `prepareThreshold` executions and Postgres may switch to a generic plan after 5 custom ones.

Custom plans are expected to be identical to today (Postgres turns `IN (list)` into `= ANY(ARRAY[...])`), so
execution time does not change in custom mode. The interesting measurement is the generic plan: with an array
parameter the planner does not know the element count (it assumes a small default), and today's generic plans are
already slower in A (1000 keys: 4.0 → 11.4 ms) and D. With H10 or H12 present, the generic plan should be the same
ordered Index Scan as the custom one, which makes a generic plan harmless; without them, the generic plan may pick
a worse shape. Expected gain: planning time (`plan_ms` column; IN with 1000 literals vs one array) and statement-cache
stability, not execution time. Likely outcome: accept only if planning time for 1000 keys is significant, or reject
as "no execution gain, dialect-specific SQL".

## Semantic equivalence

`x IN (a, b, ...)` ≡ `x = ANY(ARRAY[a, b, ...])` for non-null `x` and non-null elements (record keys are non-null
Strings). Same ORDER BY → same order. Check: `compare-results.sh` on A–E, keys 10/100/1000, custom and `--generic`.

## Portability

Postgres and H2 only (`= ANY(?)` with a JDBC array). MySQL/MariaDB/SQL Server/Oracle have no equivalent array
binding (Oracle needs `TABLE(?)` with a SQL collection type, SQL Server a TVP or `STRING_SPLIT`), so this would be
a Postgres-specific query in `JdbcOutboxRecordRepository` (the repository has a dialect hook only through
table/schema naming today: an additional dialect branch is a code-complexity cost). JPA: Hibernate 6 can bind
arrays with `array_contains` / `= any` only via native or HQL functions — not worth it. Mongo: not applicable.

## Experiment plan

Scenarios A, B, D (base schema), then B and D with the accepted index from H10 or H12.
1. `./start-postgres.sh H13 <S>` (or `--ddl` with the H10/H12 index).
2. `./run-explain.sh -c outbox-db-H13 -s H13-<S> -q ../experiments/H13/queries` plus `--generic`. Compare
   `exec_ms` and `plan_ms` with the baseline rows of `findIncompleteRecordPages` (custom and generic).
3. Equivalence: `./compare-results.sh outbox-db-H13 queries/findIncompleteRecordPages.sql ../experiments/H13/queries/findIncompleteRecordPages.sql --var keys=<n>` (+ `--generic`).
4. Optional: plan-mode probe with `plan_cache_mode=auto` (execute the PREPAREd statement 7 times with different
   key arrays and look at whether the 6th+ execution uses the generic plan and how fast it is).

## Results (per scenario): plan shape, indexes used, median ms, buffers, before → after

Method: `experiments/G2-iter1/run-scenario.sh`; same container as control (`results/G2ctl-<S>`); H13 rows in
`results/H13-<S>`. Also measured combined with the candidate indexes: `= ANY` + `completed_at IS NULL`
(`H13/queries-h11/`, mode `*-h11-h02` / `*-h11-h04`) and `= ANY` with the H10 index (mode `*-h10add`).

### H13 alone (baseline indexes)
| S | query | mode | before ms (hit) | after ms (hit) | Δ | after plan [indexes] |
|---|---|---|---|---|---|---|
| A | pages 10 | custom | 0.32 (42) | 0.29 (42) | -8% | Sort(BHS(BitmapAnd(BIS,BIS))) [rkcc,status] |
| A | pages 100 | custom | 0.86 (335) | 0.95 (335) | +11% | Sort(BHS(BitmapAnd(BIS,BIS))) [status,rkcc] |
| A | pages 1000 | custom | 4.21 (510) | 4.11 (510) | -2% | Sort(Index Scan) [status] |
| A | pages 10 | generic | 0.14 (65) | 0.14 (66) | +1% | Sort(BHS(BIS)) [rkcc] |
| A | pages 100 | generic | 0.89 (335) | 0.82 (498) | -8% | Sort(BHS(BIS)) [rkcc] |
| A | pages 1000 | generic | 11.32 (3293) | 10.07 (4785) | -11% | Sort(BHS(BIS)) [rkcc] |
| B | pages 10 | custom | 0.18 (68) | 0.19 (70) | +4% | Sort(BHS(BIS)) [rkcc] |
| B | pages 100 | custom | 6.53 (531) | 6.78 (531) | +4% | Sort(BHS(BitmapAnd(BIS,BIS))) [rkcc,status] |
| B | pages 1000 | custom | 22.49 (3864) | 22.29 (3864) | -1% | GM(Sort(P.BHS(BitmapAnd(BIS,BIS)))) [status,rkc] |
| B | pages 10 | generic | 0.21 (68) | 0.18 (70) | -13% | Sort(BHS(BIS)) [rkcc] |
| B | pages 100 | generic | 6.62 (531) | 1.40 (493) | -79% | Sort(BHS(BIS)) [rkcc] |
| B | pages 1000 | generic | 21.94 (3864) | 21.11 (4747 spill) | -4% | Sort(BHS(BIS)) [rkcc] |
| D | pages 10 | custom | 3.96 (464) | 3.99 (464) | +1% | Sort(BHS(BitmapAnd(BIS,BIS))) [status,rkc] |
| D | pages 100 | custom | 39.87 (1858 spill) | 40.45 (1858 spill) | +1% | Sort(Index Scan) [status] |
| D | pages 1000 | custom | 39.82 (1858 spill) | 39.87 (1858 spill) | +0% | Sort(Index Scan) [status] |
| D | pages 10 | generic | 6.33 (536) | 6.41 (536) | +1% | GM(Sort(P.BHS(BitmapAnd(BIS,BIS)))) [rkc,status] |
| D | pages 100 | generic | 44.33 (1904 spill) | 30.41 (2929 spill) | -31% | GM(Sort(P.BHS(BitmapAnd(BIS,BIS)))) [rkc,status] |
| D | pages 1000 | generic | 44.43 (1904 spill) | 30.88 (2929 spill) | -31% | GM(Sort(P.BHS(BitmapAnd(BIS,BIS)))) [rkc,status] |

C/E: custom ±2 %; generic 1000 C 13.5 → 7.3, E 11.2 → 9.5.

### Execution and planning ms (exec / plan), keys 100 and 1000
| S | mode | keys | IN list (control) | = ANY | H12 (IN, H02 idx) | H13+H11 (ANY, H02 idx) | H10 add (IN) | H13 with H10 add |
|---|---|---|---|---|---|---|---|---|
| A | custom | 1000 | 4.21 / 0.69 | 4.11 / 0.75 | 4.45 / 0.74 | 4.53 / 0.71 | 4.08 / 0.77 | 4.15 / 0.77 |
| A | generic | 1000 | 11.3 / 1.54 | 10.1 / 0.54 | 1.97 / 1.43 | 1.71 / 0.51 | 4.01 / 1.39 | 3.54 / 0.55 |
| B | custom | 100 | 6.53 / 0.10 | 6.78 / 0.11 | 1.21 / 0.11 | 1.33 / 0.21 | 1.19 / 0.10 | 1.33 / 0.12 |
| B | custom | 1000 | 22.5 / 0.75 | 22.3 / 0.79 | 24.0 / 0.87 | 23.9 / 0.74 | 14.9 / 1.32 | 14.9 / 0.79 |
| B | generic | 100 | 6.62 / 0.26 | **1.40** / 0.11 | 1.26 / 0.29 | **0.83** / 0.22 (Index Scan) | 1.28 / 0.22 | **0.45** / 0.15 (Index Scan) |
| B | generic | 1000 | 21.9 / 1.63 | 21.1 / 0.57 | 23.6 / 1.51 | **4.71** / 0.52 (Index Scan) | 5.48 / 1.39 | 5.72 / 0.78 |
| D | custom | 100 | 39.9 / 0.17 | 40.5 / 0.17 | 39.8 / 0.17 | 40.2 / 0.18 | 40.6 / 0.17 | 40.6 / 0.18 |
| D | generic | 100 | 44.3 / 0.23 | 30.4 / 0.28 | 40.0 / 0.23 | 40.9 / 0.28 | 43.4 / 0.22 | 26.1 / 0.18 |

(D: keys 100 and 1000 are the same 95 keys.)

Summary: custom plans are the same (±5 %, same shapes and buffers) — Postgres already turns the IN list into
`= ANY(ARRAY[...])`. Generic plans differ because the array parameter has a fixed default row estimate instead of
the list length: planning time for 1000 keys drops 1.4–1.6 → 0.5 ms, and plan choice changes in both directions
(better: B generic 100 6.6 → 1.4; with the H02 index B generic 1000 23.6 → 4.7 ms ordered Index Scan; worse: none
measured, D generic 44 → 30 ms is still a spilling sort). Planning time saved: ~0.2–1 ms per call at 1000 keys,
≤ 0.1 ms at the default 10 keys.

## Equivalence check: PASS + method
`experiments/G2-iter1/eq.sh`, ordered md5, A–E × keys 10/100/1000 × custom/generic: H13 **30/30 PASS**, H13+H11 with the
H02 index **30/30 PASS**; edge seed key_ties 0/1: H13 12/12 and H13+H11 12/12 PASS (byKey rows are the unchanged
original file). Files: `results/G2-eq-{A,B,C,D,E,edge}.txt`.

## Regression
No DDL, no write cost.

## Experimenter note
No execution gain in custom plans, so H13 alone is not worth a Postgres-only SQL branch. Its real effect is on the
generic/prepared path: one statement text, ~1 ms less planning at 1000 keys, and a generic plan whose row estimate is
small, which happens to pick the ordered Index Scan when an ordered index exists (B 1000 keys 4.7 ms with H02 + H11
predicate, vs 24 ms custom). Since pgjdbc uses custom plans for the first executions and `plan_cache_mode=auto` stayed
custom in Phase 0, this is not a dependable lever; keep as an optional add-on to whatever index wins.


## Orchestrator decision
rejected (alone). PG-only; revisit only if combined form needs it (ledger: `../STATE.md`; final report: `../99-final-report.md`)
