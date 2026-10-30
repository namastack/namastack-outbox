# Phase 3: Oracle XE 18c (18.0.0.0.0, `gvenzl/oracle-xe:18-slim-faststart`)

Scale: 200k rows (10k keys x 20), payload 300 chars NCLOB, A = 1,200 NEW (0.6%), B = 40,000 NEW (20%), 40 FAILED; 64 of 256 partitions owned (`ora_hash(key,255)` as partition; about 1,270 due keys / 10,200 due rows in B). Times: PL/SQL loop with static SQL and PL/SQL variables as real bind variables (what JDBC sends; first hard parse peeks the binds), 20 reps x 3 passes, median ms per execution, centisecond clock (so "0.5-1 ms" is the resolution); slow/wide queries are sqlplus `set timing` medians of 3 (10 ms resolution). Plans: `gather_plan_statistics` + `DBMS_XPLAN.DISPLAY_CURSOR(... 'ALLSTATS LAST +PEEKED_BINDS')`, in `phase3/oracle/plans/`. Scripts and raw logs: `phase3/oracle/` (`seed.sh`, `setidx.sh`, `run.py`, `queries.py`, `idx-*.sql`, `write.sh`, `{A,B}-{base,S,C,Pc,Pm}.log`, `write.log`).

## The decisive Oracle finding: TIMESTAMP WITH TIME ZONE columns
All time columns in the Oracle DDL are `TIMESTAMP WITH TIME ZONE`. An index on such a column is stored normalized to UTC (a hidden `SYS_NC...$ = SYS_EXTRACT_UTC(col)` column), and the optimizer does not match the plain column for ordering or `IS NULL`:
- `ORDER BY o.created_at, o.id FETCH FIRST n ROWS ONLY` never uses index order, even with a perfect index: plan is `WINDOW SORT PUSHED RANK` over a `FILTER` that runs the correlated probe for **every** outer row (10,232 probes, 169k buffers, 130 ms in B). Same with a hint forcing the new index.
- `ORDER BY SYS_EXTRACT_UTC(o.created_at), o.id` (documented function, same ordering as the TSTZ value) gives `WINDOW NOSORT STOPKEY`: the scan stops after about 106 outer rows, 1.7k buffers, **1.0 ms**.
- `completed_at IS NULL` as the leading column of a non-partial composite is NOT an index access predicate (only a table filter) unless it is written `SYS_EXTRACT_UTC(o.completed_at) IS NULL`; and the ordering then has to be `ORDER BY SYS_EXTRACT_UTC(o.completed_at), SYS_EXTRACT_UTC(o.created_at), o.id`.
- The index never returns the original TSTZ value (zone is lost), so even "covering" indexes still do a table access per outer row; it is cheap here (about 65 buffers for 106 rows).

## Recommended
Use the status-leading non-partial index (S); no `completed_at IS NULL` needed on the outer side because `status = :status` already restricts to NEW:
```sql
CREATE INDEX idx_outbox_rec_status_created ON outbox_record (status, created_at, id, partition_no, next_retry_at, record_key);
DROP INDEX idx_outbox_rec_key_cr;   -- prefix duplicate of idx_outbox_rec_k_c_c; no plan used it
```
Keep `idx_outbox_rec_k_c_c` (probe, `record_key, completed_at, created_at`), `p_s_r`, `s_r`, `stat`. Size 19 MB (table 200 MB). Query forms (Oracle SQL):
- Keys (with and without the previous-failure filter) and `findOldestDueNextRetryAtByPartitions`: head-of-key form `SELECT o.record_key ... WHERE partition_no IN (...) AND status = :status AND next_retry_at <= :now AND NOT EXISTS (probe) ORDER BY SYS_EXTRACT_UTC(o.created_at), o.id FETCH FIRST :batch ROWS ONLY`. `FETCH FIRST` (12c+) is internally the same `ROW_NUMBER` rewrite as `WHERE ROWNUM <= n` over an ordered inline view; both gave the identical plan and time. Keep `withMaxRows`.
- Oldest due: `SELECT /*+ FIRST_ROWS(1) */ o.next_retry_at ... AND NOT EXISTS (probe) ORDER BY SYS_EXTRACT_UTC(o.next_retry_at) FETCH FIRST 1 ROWS ONLY` on the existing `idx_outbox_rec_s_r` (status, next_retry_at). Without the hint the optimizer picks the created_at index and scans everything (117 ms); with `FIRST_ROWS(1)` (or `INDEX(o idx_outbox_rec_s_r)`) it is `WINDOW NOSORT STOPKEY`, 7 rows read, 113 buffers. This one needs no new index.
- Pages: keep the IN form (`status='NEW' AND completed_at IS NULL ORDER BY record_key, completed_at, created_at, id`); same rows and byte-identical to the current query. Oracle LATERAL / `CROSS APPLY (...)` over `TABLE(sys.odcivarchar2list(...))` works syntactically but is no faster (100 keys: 70 vs 70 ms; 999 keys: 100 vs 100 ms in A), and `odcivarchar2list` with 1000 literals fails with ORA-00939 (limit below 1000 args). Not recommended.
- Oldest created: `AND completed_at IS NULL` is not needed with S (S range scan narrows to NEW, 40 -> 10 ms in B, cold path). A row-goal form did not stop early (partition IN-list expands to ORs).
- Cleanup selects: `FETCH FIRST 1000 ROWS ONLY` is a real limit (`COUNT STOPKEY`-style) but only helps with a completed_at-leading index (see C): with S the plan still sorts, 190 ms vs 1,640 ms for the unlimited current query in B; C: 100 ms.

## Hot path before -> after (ms, binds, medians)
| query | A base -> S / C / Pm | B base -> S / C / Pc / Pm |
|---|---|---|
| keys, filter on (n = 100) | 3.0 -> 3.0 / 3.0 / 3.0 | 129.5 -> 1.0 / 1.0 / 1.0 / 1.0 |
| keys, filter off | 0.5 -> 4.0 / 3.0 / - | 17.5 -> 1.0 / 1.0 / - |
| oldest due (FIRST_ROWS(1), s_r) | 3.0 -> 0.0 / 3.0 (C hint-less) / 0.0 | 129 -> 0.0 / 0.0 / 0.0 / 0.0 |
| oldest created | 0 -> 0 / 0 / 0 | 20 -> 10 / 10 / 10 / 60 |
| pages 100 keys (IN) | 30 -> 30 | 80 -> 70 / 80 |
| pages 1000 keys (IN) | 100 -> 100 | 1,180 -> 630 / 670 (8k-row wide results, NCLOB-dominated) |
| cleanup select (1000) | 1,590 -> 180 / 100 | 1,640 -> 190 / 100 |
| cleanup ids (1000) | 50 -> 40 / 10 | 50 -> 40 / 20 |
A returns all of the 82-88 due keys (under the batch of 100), so there is no early stop to win; only B discriminates. Keys with filter off in A is 4 ms vs 0.5 ms (full result either way, extra probe cost); the filter-off path is not the default (`stopOnFirstFailure=true`).

## Oracle-specific questions
- No partial indexes: compared (1) non-partial completed_at-leading covering (C, same as MySQL/MariaDB), (2) function-based "partial" (Pc covering, Pm just `(CASE WHEN completed_at IS NULL THEN SYS_EXTRACT_UTC(created_at) END, CASE WHEN completed_at IS NULL THEN id END)`), (3) status-leading (S).
  - S and C: 1.0 ms in B, but C requires `SYS_EXTRACT_UTC(completed_at) IS NULL` and a three-column ORDER BY (ugly and fragile); C is the only one that also serves the cleanup selects. S needs the least query change and is the same hot-path speed.
  - Pc/Pm (entirely-NULL keys are not indexed): 1.0 ms and 3 MB (Pc, B; completed rows are not indexed) instead of 19-21 MB; the query must repeat the expressions exactly (`CASE WHEN o.completed_at IS NULL THEN SYS_EXTRACT_UTC(o.created_at) END`, also in ORDER BY and the probe's correlation; Pm is the same query with plain `o.record_key` etc. plus the expression as the filter and sort key). Works with binds. Smallest and cheapest to maintain, but long query text and expressions on 6 columns for Pc; Pm leaves table access for the rest.
- `completed_at IS NULL` as leading column: usable as access predicate only in the `SYS_EXTRACT_UTC(completed_at) IS NULL` spelling (above). Otherwise table filter.
- FETCH FIRST vs ROWNUM: same plan (`VIEW` / `WINDOW NOSORT STOPKEY`). Early stop only if ORDER BY matches the index order (UTC expression), otherwise `WINDOW SORT PUSHED RANK` computes everything.
- Bind variables: with binds the plans above are the peeked plans (plan files show peeked binds); literal and bind runs give the same plans and times (0-10 ms vs 1 ms). Plans do not depend on the bind values here. The partition IN-list is expanded into 64 bitmap-OR branches by the current query (`BITMAP CONVERSION`), which the new queries avoid.
- IN-list: 1000 expressions OK, 1001 -> ORA-01795. 64 partitions + keys list fine; pages with 1000 keys is exactly at the limit (fine), batches above 1000 must be split. `CROSS APPLY (VALUES)` equivalent does not exist; `TABLE(odcivarchar2list(...))` is limited to under 1000 args.
- Column-group statistics: not needed, no misestimate that changed a plan (the NOSORT/STOPKEY plan was chosen by cost; only oldest-due needed a `FIRST_ROWS(1)` hint).
- `idx_outbox_rec_key_cr` drop: no regression (probe and pages use `idx_outbox_rec_k_c_c`; plans show no use of key_cr).

## Equivalence
Full ordered result without the limit, same data: keys sets identical to the current query (A 82 / 88 keys, B 1,273 / 1,279; filter on / off), `created_at` sequences equal (the current ORDER BY MIN(created_at) is nondeterministic on ties, so exact key order differs only inside ties), oldest-due and oldest-created values equal, pages IN identical (same row/ordering hash, 5,600 and 56,000 rows). Pc: sets equal; the order check printed False only because the expression returns a plain TIMESTAMP (format of the compared string), Pm True.

## Write cost (A data, 50k insert / update to COMPLETED / delete, ms, two runs, noisy; `write.log`)
base 56350, 51040 / 23200, 14480 / 30390, 27920; S 49310, 50940 / 24470, 20550 / 33160, 27480; C 51450, 54160 / 24430, 27730 / 33920, 30310; Pc 50930, 49800 / 19070, 15660 / 28840, 29310; Pm 42010, 51500 / 18600, 14440 / 29300, 26120. The insert is dominated by the NCLOB table (slow disk). Update (NEW -> COMPLETED): S about +0-40%, C up to +90% in one run (key moves in a 20 MB index), FBI (Pc/Pm) equal or faster than base (the entry is removed, not moved).

## Blockers / caveats
- All of the benefit depends on `SYS_EXTRACT_UTC(...)` in ORDER BY (and the rewritten expressions); a plain `ORDER BY created_at` gets none of it. SYS_EXTRACT_UTC is documented SQL, and it orders the same as the TSTZ value (instants).
- Oldest-due needs `FIRST_ROWS(1)` (or an index hint).
- Hints in JDBC SQL are plain comments, safe on all drivers; no Oracle syntax blocker for FETCH FIRST (12c+; XE 18c fine).
- DDL (`outbox-tables.sql`) creates fine unchanged; a new index belongs into the same `EXECUTE IMMEDIATE` block. Index names must stay under 30 chars on older versions (`idx_outbox_rec_status_created` is 29).
- Host-level: `select_catalog_role` is needed for DISPLAY_CURSOR (benchmarking only).
