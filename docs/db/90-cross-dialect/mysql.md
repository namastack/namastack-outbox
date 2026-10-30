# Phase 3: MySQL 9.2

Scale: 1M rows (50k keys x 20), scenario A = 6,000 NEW (0.6%), B = 200,000 NEW (20%), 200 FAILED, 100 keys not due; 64 of 256 partitions owned. InnoDB, `innodb_buffer_pool_size=1G`, data on tmpfs, `TIMESTAMP` (second precision, so created_at ties exist and the id tiebreak is exercised). Times are medians of 3 warm runs, server side (SYSDATE deltas), result rows included. Scripts and raw logs: `phase3/` (`seed.sh`, `queries.py`, `run.py`, `force.py`, `idx-*.sql`, `mysql/*.log`, `mysql/plans/`).

## Recommended: set C (completed_at-leading, non-partial)
```sql
ALTER TABLE outbox_record
  ADD INDEX idx_outbox_record_incomplete_created (completed_at, created_at, id, partition_no, status, next_retry_at, record_key),
  DROP INDEX idx_outbox_record_record_key_created;   -- prefix duplicate of record_key_completed_created
```
`id` must be explicit: without it MySQL does a filesort of the whole outer set before the anti-join (no early stop; 240 ms in B). Keep `record_key_completed_created` (probe), `status_retry`, `status`, `partition_status_retry`. The same index serves cleanup (`completed_at < cutoff ORDER BY completed_at`), which the baseline answers with a 1M-row filesort.

## Query forms
- Keys (both) and oldest-due: the H60 head-of-key form unchanged (`NOT EXISTS`, `completed_at IS NULL` on outer and probe, `ORDER BY o.created_at, o.id LIMIT :batch`). `LIMIT n` is the syntax. The planner chooses the early-stop plan by itself with set C (anti-join over `idx_outbox_record_incomplete_created`; ~600-800 index rows read for LIMIT 100 of 200k, no sort).
- Pages: LATERAL is supported (8.0.14+). The IN form with `status='NEW' AND completed_at IS NULL ORDER BY record_key, completed_at, created_at, id` is also fine. Both return identical per-key order.
- Oldest created: adding `AND completed_at IS NULL` does not help on MySQL (205 ms in B, `MIN` still scans the partition index range). `SELECT created_at ... ORDER BY created_at LIMIT 1` (no row instead of NULL) takes 0.2 ms with the covering index (FORCE INDEX; unforced 0.7-1.0 ms measured on set S).
- Cleanup: `... ORDER BY completed_at LIMIT :limit`.

## Hot path, before -> after (ms), set C unforced
| query | A before -> after | B before -> after |
|---|---|---|
| keys, filter on | 16.8 -> 8.4 | 510 -> 2.4 |
| keys, filter off | 18.1 -> 10.3 | 239 -> 4.9 |
| oldest due (filter on) | 15.5 -> 1.2 | 491 -> 1.3 |
| pages 100 keys: IN / LATERAL | 19 -> 7.7 / 3.5 | 26 -> 18 / 7.9 |
| pages 1000 keys: IN / LATERAL | 71 -> 36 / 24 | 246 -> 180 / 120 |
| oldest created (+ completed_at IS NULL) | 7.8 -> 6.9 | 204 -> 206 (LIMIT 1 form: 0.2) |
| cleanup select (limit 1000) | 5321 -> 15 | 4534 -> 16 |
| failed before | 3 -> 3.4 | 2.4 -> 3.6 |
In A the optimizer still picks the partition index plus a sort (about 1,400 due rows, 7 ms); with FORCE INDEX the early-stop plan gives 1.5 ms. B is where it matters.

## Status-leading (set S) vs C
S = `(status, created_at, id, partition_no, next_retry_at, record_key)` + `(record_key, status, created_at)` + status predicate (`older.status IN ('NEW','FAILED')`, outer without `completed_at`). Works (keys B 3.3/5.6, oldest due 1.7) but is no better than C, needs two new indexes, and the cleanup select needs another `(status, completed_at)` index (4.2 s without it, 15 ms with). Write cost is higher (below). Choose C.

## Equivalence
Same data, full result without LIMIT: key sets identical (A 450/475, B 6200/6225 keys), oldest-due and oldest-created values equal, pages IN and LATERAL byte-identical (IN) / identical per key (LATERAL). Key order differs from the current query only inside created_at ties (current ORDER BY MIN(created_at) is nondeterministic there; the new one is total on (created_at, id)). Forced and unforced plans return the same rows.

## Write cost (B, 50k inserts / 50k status updates to COMPLETED / 50k deletes, ms; noisy, single run)
base 2630 / 2848 / 1690; C 2123 / 3095 / 1537 (about neutral); S 2870 / 3966 / 1814 (+9% / +39%).

## Other questions
- Statistics: estimates for `status='NEW'` were 2x off (402k vs 200k) but plans were right; no histogram needed, not tested.
- Dropping `record_key_created`: no regression (probe and page queries use `record_key_completed_created`).
- No partial index / INCLUDE / NULLS syntax needed. No IN-list limit issue at 1000 keys. Blocker: none. Connector/J `maxRows` behavior was not checked; a real `LIMIT` is portable and removes the question.
- Current `JdbcOutboxRecordRepository` has no dialect-specific SQL; `LIMIT :n` is shared by MySQL, MariaDB and PostgreSQL.
