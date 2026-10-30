# Phase 3: MariaDB 12 (12.3.3)

Same method and data as `mysql.md` (1M rows, A = 0.6% NEW, B = 20% NEW, 64/256 partitions, tmpfs, 1G buffer pool). Plans via `ANALYZE FORMAT=JSON`. Scripts/logs: `phase3/` and `phase3/mariadb/`.

## Recommended: set C (same as MySQL)
```sql
ALTER TABLE outbox_record
  ADD INDEX idx_outbox_record_incomplete_created (completed_at, created_at, id, partition_no, status, next_retry_at, record_key),
  DROP INDEX idx_outbox_record_record_key_created;
```
Query forms: head-of-key keys / oldest-due (`NOT EXISTS`, `ORDER BY o.created_at, o.id LIMIT :batch`), cleanup `... ORDER BY completed_at LIMIT :limit`, pages in the IN form (`status='NEW' AND completed_at IS NULL ORDER BY record_key, completed_at, created_at, id`). No LATERAL in MariaDB (not tested), so the IN form stays. Early stop confirmed: the plan reads `idx_outbox_record_incomplete_created` with r_rows 797 for LIMIT 100 of 200k, no filesort, and no hint is needed.

## Hot path, before -> after (ms), set C
| query | A before -> after | B before -> after |
|---|---|---|
| keys, filter on | 9.8 -> 1.7 | 308 -> 1.6 |
| keys, filter off | 4.8 -> 1.4 | 148 -> 1.4 |
| oldest due | 8.6 -> 0.9 | 293 -> 1.1 |
| pages 100 / 1000 (IN) | 10.1 / 41 -> 6.2 / 27 | 18.7 / 163 -> 14.9 / 159 |
| oldest created (+ completed_at IS NULL) | 3.6 -> 2.0 | 113 -> 51 (LIMIT 1 form: 0.3, FORCE INDEX; 0.3 unforced on S) |
| cleanup select (limit 1000) | 1553 -> 12 | 1454 -> 21 |
| failed before | 4.3 -> 3.3 | 4.3 -> 1.6 |

## Status-leading (set S) vs C
S (`(status, created_at, id, ...)` + `(record_key, status, created_at)`, status predicate) matches C on the keys / oldest-due queries (1.7/1.4/0.9 ms in B) and is better on pages (100 keys 8.5, 1000 keys 75 ms in B vs 159 for C), but `+completed_at IS NULL` oldest-created stays at 114 ms, cleanup stays at 1.9 s unless a third index `(status, completed_at)` is added (8 ms then), and write cost is higher. C is simpler and has the best total; S is a valid alternative if pages dominate.

## Equivalence
Same as MySQL: key sets identical in every case (A 450/475, B 6200/6225), oldest-due and oldest-created equal, pages byte-identical to the current query. Key order differs from the current query only within created_at ties (current order is nondeterministic there). Forced and unforced plans return the same rows.

## Write cost (B, 50k insert / update / delete, ms, single run)
base 979 / 1554 / 675; C 803 / 1749 / 544 (neutral); S 1334 / 1977 / 730 (+36% insert, +27% update).

## Other
- `LIMIT n` syntax (also `LIMIT n OFFSET m`); early stop works as above. No partial index, INCLUDE, or NULLS needed. Schema differs from MySQL only by explicit `DEFAULT NULL`. Blockers: none. Statistics: no misestimate issue seen. Dropping `record_key_created` has no regression.
