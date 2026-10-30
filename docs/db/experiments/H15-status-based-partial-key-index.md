# H15: Status-based partial per-key index `WHERE status IN ('NEW','FAILED')` / `<> 'COMPLETED'` for the bulk load
Status: proposed (user idea, G2 side of H09)
Target queries: `findIncompleteRecordPages`, `findIncompleteRecordsByRecordKey`; same index is the G1 predecessor-probe candidate (see H16).

## Change
```sql
-- H15a (indexes-in.sql)
CREATE INDEX idx_outbox_record_open_key_created ON outbox_record (record_key, created_at, id) WHERE status IN ('NEW','FAILED');
-- H15b (indexes-ne.sql), alternative predicate
CREATE INDEX idx_outbox_record_open_key_created ON outbox_record (record_key, created_at, id) WHERE status <> 'COMPLETED';
```
Query: unchanged shape, `status` inlined as the literal `'NEW'` (`H15/queries/`):
`... WHERE record_key IN (...) AND status = 'NEW' ORDER BY record_key, created_at, id` (and the byKey twin).
`H15/queries-redundant/` adds a literal `AND status <> 'COMPLETED'` as fallback if PG cannot prove `= 'NEW'` implies `<> 'COMPLETED'`.

## Rationale / expected plan
- Why literal: the repository always binds `NEW` (both `findByKeyAndStatusQuery` and `findByKeysAndStatusQuery`; the SPI methods carry no status argument). Postgres matches a partial index only when the
  clause provably implies the predicate at plan time. With `status = $n` that works in custom plans only (planner sees the value) and never in generic plans (`plan_cache_mode`, pgjdbc switches after 5 executions). A literal is safe in both.
- `status = 'NEW'` implies `status IN ('NEW','FAILED')` (predtest proves ScalarArrayOp predicates by element). `= 'NEW'` implies `<> 'COMPLETED'` is expected to be provable via btree strategy proof (constants differ) - **verify with EXPLAIN**; the redundant-clause variant is the fallback.
- Estimation: the planner no longer needs `completed_at` (no status x completed_at independence); rows = keys x rows/key x sel(status='NEW') from the status MCV (B: 20%), roughly 1000 x 20 x 0.2 = 4k vs actual 8k (B) - 2x off instead of 450x. That should flip the choice to the ordered SAOP Index Scan on the small partial index: expected B 1000 ~5 ms, D ~7 ms (the measured ordered-scan numbers), no Sort, no spill. If the planner still picks bitmap + Sort in D (19k random heap fetches at `random_page_cost=4`), H14 on top of this index is the guaranteed path.
- Index size: only NEW/FAILED rows (B: 20% of 1M, A: <1%), ~12-45 MB depending on scenario; write cost only on insert and on transitions to/from the partial set (completion removes the entry).
- Not generic-safe if the code ever binds another status: documented in the SQL comment/KDoc; `deleteByRecordKeyAndStatus` etc. keep their own queries.

## Semantic equivalence argument
Query text differs only by inlining the bound constant `'NEW'`: identical rows and order by construction. Partial index choice never changes results.
For the invariant: nothing beyond the existing semantics (no `completed_at` involved). The `status IN (NEW, FAILED)` index contains every NEW row (and FAILED, filtered out by the query predicate).
Check: ordered md5 vs original, A-E, keys 10/100/1000, custom + generic, edge seed.

## Portability
- postgres: yes (partial). jpa: `@Table(indexes=...)` cannot express partial indexes - DDL is the JDBC schema file (JPA uses it); the JPQL has `status = :status` (a bound enum) and works with the plan only in custom mode on Postgres.
- sqlserver: filtered index `WHERE status IN ('NEW','FAILED')` is allowed (IN permitted; `<>` also), but filtered-index matching with parameters needs `OPTION (RECOMPILE)` or a literal - literal inlined here.
- mysql / mariadb / oracle: no partial indexes. Portable equivalent: non-partial `(record_key, status, created_at, id)` = H10 (G2 only), or rkcc+id with `completed_at IS NULL` (see H16).
- mongo: partial index `{recordKey,createdAt,_id}` with `partialFilterExpression: {status: {$in:[NEW,FAILED]}}` is possible, and Mongo's `$in`/`$ne` limits apply - needs `status` predicate in the query to use it; Phase 3.

## Experiment plan
States (post-seed, in place): control; +H15a; replace with H15b (`queries` then `queries-redundant`). Scenarios A, B, D, E; keys 10/100/1000; custom and generic; median of 3, hits, spill check, plus costs-on EXPLAIN (estimated vs actual rows) for B1000 and D. Also with `random_page_cost=1.1` in D. Report whether the implication is used (index named in the plan, no Filter on status). Write cost with `write-cost.sh` (fresh container, DDL pre-seed): +add-only vs base. Regression: full read suite diff (H02-style) since `status IN` predicates in other queries (H32/G1) may now match.

## Results (per scenario): plan shape, indexes used, median ms, buffers, before -> after
Pending.
## Equivalence check: PASS/FAIL + method
Pending.
## Regression: other queries whose plan changed; write cost; index size
Pending.
## Decision + reasoning
Pending.

## Experimenter note (iteration 2)
Not run (deprioritised). The query-level status variants were measured instead (H14 item 3, `G2i2-*` P1/P2/LP): redundant status conjuncts next to `status = 'NEW'` do not help estimates or index use (P2 worse, generic up to 227 ms). The `completed_at IS NULL` form on the existing key index already gives an ordered per-key range (H16 U2a/U2b), so a status-partial index is not needed for G2.


## Orchestrator decision
not run (deprioritised). user idea is query-only (ledger: `../STATE.md`; final report: `../99-final-report.md`)
