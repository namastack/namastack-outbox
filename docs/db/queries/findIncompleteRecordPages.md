# Dossier: `findIncompleteRecordPages(recordKeys)` (group G2, hot path)

## SQL (source of truth: `JdbcOutboxRecordRepository.findByKeysAndStatusQuery`)

```sql
SELECT * FROM outbox_record
WHERE record_key IN (:recordKeys) AND status = :status      -- status bound as 'NEW'
ORDER BY record_key, created_at, id
```
Harness: `harness/queries/findIncompleteRecordPages.sql` (variants keys=10/100/1000; generic branch with
`$1..$n` key placeholders and status as `$n+1`).

## Contract and semantics (KDoc in `OutboxRecordRepository`, `OutboxRecordPage`)

- One bulk read, then `groupBy { recordKey }` in Kotlin; one `OutboxRecordPage` per requested key (empty page for
  keys without rows). `MAX_RECORD_KEYS_PER_FETCH = 1000`, enforced with `require`.
- Each page: ascending `(created_at, id)`. Only `status = 'NEW'` rows (FAILED excluded); future retries
  (`next_retry_at > now`) are included, because the worker must see them to stop/continue the key
  (`OutboxProcessingScheduler.processRecord` → `canBeRetried`).
- **No per-key row cap, no window function, no SQL limit**: every NEW row of every selected key is loaded
  (a page "does not limit rows or bytes"). With `stopOnFirstFailure=true` the worker stops at the first record not
  completed, so in D (200 NEW per key) most loaded rows of a blocked/not-due key are never processed. A per-key cap
  would change the contract (and need a window function or LATERAL + LIMIT); out of scope for this investigation,
  noted as a follow-up for the library.
- Cross-key order of the result set is not observable by the caller (`groupBy` keeps per-key encounter order only;
  `recordKeys.associateWith` iterates the requested set). The investigation still requires identical output order.
- Invariant used by H11/H12: `status = 'NEW'` ⇒ `completed_at IS NULL` (only `markCompleted` sets `completedAt`,
  together with `COMPLETED`; not schema-enforced).

## Callers

`OutboxProcessingScheduler.processBatch`, every poll that found keys; keys come from `findRecordKeysInPartitions`
(`withMaxRows(batchSize)`, default 10), chunked by 1000. Then one task per key on the `TaskExecutor`.
JPA (`JpaOutboxRecordRepository.findByRecordKeysAndStatusQuery`, JPQL constructor expression, same predicate and
ORDER BY) and Mongo (`Criteria.in(recordKeys).and(status).is(NEW)`, sort `recordKey, createdAt, _id`) implement the
same contract.

## Baseline (01-baseline.md; custom, ms (shared hits))

| keys | A | B | C | D | E |
|---|---|---|---|---|---|
| 10 | 0.31 (42) | 0.18 (68) | 0.12 (38) | 3.9 (464) | 0.31 (42) |
| 100 | 0.84 (335) | 6.6 (531) | 0.68 (345) | 40 (1,858) spill | 0.82 (334) |
| 1000 | 4.0 (510) | 22 (3,864) w2 | 12 (1,803) w2 | 40 (1,858) spill | 4.1 (509) |

Generic: similar except A 1000 keys 4.0 → 11.4 ms and D +10%. Plans always end in a full `Sort`:
- A/E: BitmapAnd of `record_key_completed_created` and `status`, or a `status` index scan with the key list as filter.
- B: BitmapAnd with a **200k-entry `status` bitmap** (that bitmap is most of the 6.6 ms at 100 keys); 1000 keys parallel.
- D: `status` index scan over all 20k NEW rows, key filter, **external merge sort 12 MB** (19k rows × ~650 B,
  `work_mem=4MB`). D has 95 due keys, so keys=100 and 1000 are the same 95 keys.

Root causes: (1) no index combines `record_key` + "NEW/incomplete" + `(created_at, id)` order — `status` is in no
key-leading index, and the existing `record_key_completed_created` is never probed with `completed_at IS NULL`
because the query does not say it; (2) `SELECT *` of wide rows makes any full sort of large pages spill.

Scaling check (target 6.1): cost should scale with incomplete rows of the requested keys. Today B pays for all
200k NEW rows in the `status` bitmap even for 100 keys; D pays a spill. After the fix both should scale with
rows returned.

## Hypotheses (iteration 1)

| H | Change | Expected | Notes |
|---|---|---|---|
| [H10](../experiments/H10-key-status-created-index.md) | index `(record_key, status, created_at, id)` replacing unused `record_key_created` | ordered Index Scan, no Sort, generic-safe; B 6.6/22 → ~0.7/5 ms; D 40 → ~10 ms if the ordered path is chosen | portable to every dialect, JPA, Mongo; could also serve G1's anti join if it is rewritten to `status IN ('NEW','FAILED')` |
| [H11](../experiments/H11-completed-at-null-predicate.md) | rewrite only: `AND completed_at IS NULL` | existing `record_key_completed_created` probed with `IS NULL`; no status bitmap; B → ~1/6 ms; D via Incremental Sort (no spill) or unchanged | zero DDL, zero write cost; relies on the NEW ⇒ completed_at NULL invariant |
| [H12](../experiments/H12-partial-incomplete-key-index.md) | G1's partial `(record_key, created_at, id) WHERE completed_at IS NULL` + H11 predicate | ordered Index Scan on a tiny index, no Sort, generic-safe | best if G1 adds the index anyway; PG + SQL Server only; H12-alt (`WHERE status='NEW'`) is not generic-safe |
| [H13](../experiments/H13-any-array-parameter.md) | `record_key = ANY(?)` array parameter | same custom plan; planning time and statement-cache stability | Postgres-only SQL; low priority |

Cost-model risk for all ordered-index hypotheses in D: `record_key` order is uncorrelated with the heap, so the
planner prices 19k random heap fetches above bitmap + sort at `random_page_cost=4`; experiments measure D with
`random_page_cost=1.1` and `enable_bitmapscan=off` too.

Rejected during analysis (no experiment):
- LATERAL per key (`unnest(keys) k CROSS JOIN LATERAL (SELECT ... WHERE record_key = k ORDER BY created_at, id)`):
  without a per-key LIMIT it reads the same rows as an SAOP index scan, and the outer ORDER BY still needs a sort
  because the planner cannot derive order from the lateral output. Only useful with a per-key cap, which the
  contract forbids.
- Dropping `record_key` from ORDER BY / ordering by `(created_at, id)` with a `(status, created_at, id)` index to
  follow heap order in D: changes the output order (caller-invisible, but violates the investigation rule) and adds
  an index that is bad for small key sets.
- Raising `work_mem` to avoid the D spill: deployment setting, not a library change; mention in docs only.

## Iteration 1 outcome (measured, `G2-iter1/`, H10-H13)
All variants output-equivalent. H10 `(record_key, status, created_at, id)` replacing `record_key_created`: B100 6.5 -> 1.3 ms, B1000 22.5 -> 15.7 (custom; generic 5.9), D 40 -> 40 (12 MB sort spill stays), WAL insert +2.7%, update +4.3%, +75 MB. H11/H13 rejected alone; H12 refined. Root problem: at 1000 keys (B) and in D the planner estimates ~42 rows vs 19k actual (status x completed_at independence), refuses the ordered Index Scan and picks bitmap + full Sort of wide rows (spills). Forced ordered scan: B 4.8, D 6.6 ms.

## Iteration 2 (proposed, not yet measured)
Direction: make the ordered per-key path the only plan, and fix the estimation instead of tuning around it.

| H | Change | Expected | Notes |
|---|---|---|---|
| [H14](../experiments/H14-lateral-per-key-ordered-probe.md) | `unnest(keys)` sorted outer + `CROSS JOIN LATERAL` ordered probe per key (variants: with/without top ORDER BY, +`completed_at IS NULL`) | no row-level Sort, no spill, generic-safe: B1000 22 -> ~5 ms, D 40 -> ~7 ms | dialect-specific SQL (LATERAL / CROSS APPLY); MariaDB keeps IN; works on H10 index with zero extra DDL |
| [H15](../experiments/H15-status-based-partial-key-index.md) | partial `(record_key, created_at, id) WHERE status IN ('NEW','FAILED')` (or `<> 'COMPLETED'`) + literal `status = 'NEW'` | planner estimate within 2x, ordered Index Scan chosen at 1000 keys | partial match needs literal (generic plans); repository always binds NEW |
| [H16](../experiments/H16-unified-per-key-index.md) | one per-key index serving G1 head probe + G2 (U1 partial status-based / U2 portable rkcc+id) | G2 as H15, one small index for both groups | decided jointly with G1 H06/H08/H09 |

Order the consumer relies on: only ascending `(created_at, id)` per key (`groupBy` keeps in-group encounter order; page order comes from the requested set). `ORDER BY record_key` is irrelevant to the consumer; dropping it alone gives no cheaper plan, only the LATERAL shape (H14n) does. Verification: strict ordered md5 and per-key digest comparison (see H14).

## Outcome

Pending (iteration 2 proposed).
