# H42: bounded delete as one statement instead of "select ids, then deleteByIds"
Status: measured
Group: G5 (cleanup). Iteration 1. Builds on H40 (index) and H41 (SQL limit).
Target queries: `deleteCompletedRecords(completedBefore, limit)`, `deleteFailedRecords(lastRetryBefore, limit)`.

## Change

Postgres:

```sql
DELETE FROM outbox_record
WHERE id IN (
  SELECT id FROM outbox_record
  WHERE status = :status AND completed_at < :cutoff     -- FAILED: next_retry_at < :cutoff
  ORDER BY completed_at                                  -- FAILED: ORDER BY next_retry_at
  LIMIT :limit
);
```

Code diff (description only): `deleteCompletedRecords` / `deleteFailedRecords` execute this one `update()` and
return its row count instead of `query(...).list()` + `deleteByIds(ids)`. `deleteByIds` stays (public SPI).

## Soundness of the current pattern (select ids + `deleteByIds`)

- Correct and bounded: both steps run in one `transactionTemplate` transaction; the id select returns at most
  `limit` ids (via `withMaxRows`), `deleteByIds` uses the primary key (`Delete -> Bitmap Heap Scan -> Bitmap Index
  Scan pkey`, 1000 ids: A 3.2 ms / 3.9k buffers). Nothing wrong with the delete step.
- All the cost is in step 1 (157-273 ms as executed, H40/H41 fix it).
- Costs of the two-step form: one extra round trip; up to `limit` ids are transferred to the client and bound back
  as `limit` parameters (Oracle 1000-expression and SQL Server 2100-parameter IN limits cap `limit`, ADR 0003);
  statement text varies with the id count (no statement reuse on Postgres / plan cache churn on SQL Server).
- Concurrency: at READ COMMITTED a row updated between the two steps is still deleted (documented in the KDoc and
  ADR 0003). Two instances running the cleanup concurrently select the same ids; the second blocks on the first
  one's row locks and then deletes 0 rows. Wasted work, but correct (ADR 0003 asks applications to coordinate).

## Rationale / expected plan

```
Delete on outbox_record
  -> Nested Loop
       -> HashAggregate (or Unique)            -- ids from the subquery
            -> Limit -> Index Scan idx_..._status_completed (status = 'COMPLETED' AND completed_at < cutoff)
       -> Index Scan using outbox_record_pkey (id = subquery.id)
```

Expected with H40: ~3-4 ms for 1000 rows (the delete itself dominates, like `deleteByIds`), i.e. about the same DB
time as H40+H41 select (~1 ms) + `deleteByIds` (~3 ms), minus one round trip and the parameter binding of 1000 ids.
Without H40: the subquery is the H41 base plan (top-N over a seq scan, ~40-80 ms). The value is not DB time but:
no IN-list limit on `limit` (Oracle/SQL Server), constant statement text, one round trip, and a narrower race
window (Postgres re-checks only `id IN (...)` on a concurrently updated row, so the documented race stays possible;
it gets narrower, not closed).

## Semantic equivalence argument

Same set of deleted rows as "first `limit` ids by `completed_at`" + `DELETE ... WHERE id IN (ids)` when no
concurrent change happens between the steps: the subquery is H41's id select. The return value (deleted row count)
is the same. Check: `compare-results.sh <c> experiments/H41/eq/orig_deleteCompletedRecords_selectIds_maxrows.sql
experiments/H42/eq/rewr_deleteCompletedRecords_ids.sql --var cutpct=0.1` (and 0.9): the rewritten file returns the
ids deleted by the single statement (rolled back). "FAIL (same rows, different order)" counts as PASS (RETURNING
order is unspecified; a DELETE is set-based).

## Portability

- Postgres, Oracle 12c+ (`FETCH FIRST n ROWS ONLY` in the subquery), H2: as above.
- SQL Server: `DELETE FROM t WHERE id IN (SELECT TOP (@n) id FROM t WHERE ... ORDER BY completed_at)`.
- MySQL / MariaDB: `LIMIT` inside an `IN` subquery is rejected (error 1235) and the target table cannot be referenced
  in a subquery of its own DELETE (error 1093). Workaround: `DELETE t FROM outbox_record t JOIN (SELECT id FROM
  outbox_record WHERE ... ORDER BY completed_at LIMIT ?) x ON x.id = t.id` (MySQL 8 and MariaDB accept a derived
  table in a multi-table DELETE). So three dialect variants are needed.
- JPA: HQL does not support a limit inside a subquery in a portable way; JPA keeps "select ids, delete by ids"
  (ADR 0003 already says so). Mongo: no limited delete; keeps the two-step form.

## Experiment plan

1. Scenarios A, B, D; index sets base and H40 add.
2. `run-explain.sh -q experiments/H42/queries` (custom; writes are rolled back), cutpct 0.1 / 0.9, limit 1000 / 10000.
3. Compare with baseline `deleteCompletedRecords_selectIds-serial` + `deleteByIds`, and with H41 select + `deleteByIds`.
4. Equivalence as above (A, B, D, edge).
5. Accept only if it is not slower than H41 + `deleteByIds`; the main argument is the IN-list limit, so the
   decision is mostly a maintainers' call (three dialect variants in exchange for an unbounded `limit`).

Setup: Postgres 18, scenarios A/B/D (1M rows, tmpfs, shared_buffers 1GB), every state measured IN PLACE on one container per scenario on top of **H08a**
(`experiments/H08/indexes.sql`), `VACUUM ANALYZE` after each DDL. Driver: `experiments/G4G5-iter1/run-scenario.sh` (states h08a, h32psr, h32ii, h32ii-nostatus,
h31a-nopsr, h31a, h31b, h40add, h40repl, h07), equivalence `eq.sh`, write cost `run-writecost.sh`, extras `run-extra.sh`. Raw: `results/G4G5-{A,B,D}/`,
`results/G4G5-{A,B}-g5/` (cleanup), `results/G4G5-{A,B}-hot/` (hot-path regression subset), `results/G4G5x-{A,B}/`, `results/G4G5-wc-A/`, `results/G4G5-eq-*.txt`.
Baseline = `01-baseline.md`; "H08a ctl" = control remeasured (identical to baseline for these queries).
Format: median ms (shared buffers). Raw `.txt` plans of the three H32 queries were overwritten by the +INC runs (same file name and tag); `summary.tsv` has both rows (first = original SQL, second = +INC).

Single statement `DELETE FROM outbox_record WHERE id IN (SELECT id ... ORDER BY completed_at LIMIT 1000)` (rolled back), custom plan, ms (buffers). Reference for the two-step form: H41 id select + `deleteByIds` (baseline 3.0-3.5 ms, 3,926 buffers, 1000 ids).

| scen / cutoff | H08a ctl (no index) | + `(status,completed_at)` add | replace | H07 index | two-step with index (H41 select 0.2-0.3 + deleteByIds ~3.0-3.2) |
|---|---|---|---|---|---|
| A 10% | 154 (95,863) | **3.5 (5,098)** | 3.4 | 3.4 (5,103) | ~3.3-3.5 |
| A 90% | 286 | 3.5 | 3.35 | 3.4 | ~3.3-3.5 |
| B 10% | 148 (94,393) | 3.3 (5,098) | 3.3 | 3.6 | ~3.3-3.5 |
| B 90% | 254 | 3.4 | 3.3 | 3.3 | ~3.3-3.5 |
| deleteFailedRecords_single A / B | 0.42 / 0.44 | 0.75 / 0.47 | 0.39 / 0.41 | 0.43 / 0.42 | ~0.1 + deleteByIds of ~100 ids |

- Plan with an index: `Delete -> Nested Loop -> HashAggregate -> Limit -> Index Scan status_completed/completed_created, then pkey scan`. Without an index the subquery runs serial (a DELETE plan has no parallel workers): 148-286 ms, i.e. 2-3x worse than the H41 select (77-132 ms parallel) + deleteByIds.
- With an index the single statement equals select + `deleteByIds` in DB time (3.3-3.6 ms vs ~3.4-3.7 ms), minus one round trip and the 1000 bound ids. No DB-time gain; the benefit is only the removal of the IN-list limit on `limit` (Oracle 1000, SQL Server 2100 parameters) and constant statement text.

## Equivalence check
`results/G4G5-eq-{A,B,D,edge}.txt`: `H42/eq/rewr_deleteCompletedRecords_ids.sql` (ids deleted by the single statement, rolled back) vs the `withMaxRows` original (`H41/eq/orig_deleteCompletedRecords_selectIds_maxrows.sql`), cutpct 0.1 / 0.9, limit 1 / 1000 / 100000: **0 FAIL on A, B, D, edge** (exact order match even; set-equality is what a DELETE needs).

## Regression
Query-only change; needs the same index as H40/H41 to be worthwhile. Three dialect forms (Postgres/Oracle/SQL Server subquery with limit, MySQL/MariaDB derived-table multi-table DELETE); JPA and Mongo keep select-ids + delete-by-ids.

## Experimenter note
Not slower than H41 + `deleteByIds` once an ordered index exists, not faster either; without an index it is worse. Maintainers' call (robustness vs three dialect variants).

## Decision + reasoning


## Orchestrator decision
rejected. only benefit IN-list limit → open question (ledger: `../STATE.md`; final report: `../99-final-report.md`)
