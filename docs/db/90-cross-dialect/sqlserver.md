# Phase 3: SQL Server 2022 (RTM-CU27, 16.0.4295)

Scale: 200k rows (10k keys x 20), not 1M (host disk had ~2.6 GB free after the image pull). Scenario A = 1,200 NEW (0.6%), B = 40,000 NEW (20%), 40 FAILED, 20 keys not due; 64 of 256 partitions owned (partition = first 4 bytes of MD5(key) mod 256, about 1,200 due keys / 9,700 due rows in B). Same data shape as `phase3/` MySQL. Container `mcr.microsoft.com/mssql/server:2022-latest`, `MSSQL_MEMORY_LIMIT_MB=2048`, anonymous volume (tmpfs is not used; SQL Server wants O_DIRECT), removed afterwards. Times are medians of 3 warm timings, server side; sub-60 ms queries are timed as 20-rep loops divided by 20 (the SYSDATETIME tick is about 4 ms on Linux). Scripts and raw logs: `phase3/sqlserver/` (`seed.sh`, `all.sh`, `run.py`, `queries.py`, `idx-F/C/undo.sql`, `write.sh`, `{A,B}-{base,F,C}.log`, `plans/`). `sq.sh` uses `sqlcmd -I`: filtered indexes require `QUOTED_IDENTIFIER ON`, which sqlcmd turns off by default (JDBC has it on).

## Recommended
```sql
-- F (Postgres-partial equivalent):
CREATE INDEX idx_outbox_record_incomplete_created ON outbox_record (created_at, id)
  INCLUDE (partition_no, status, next_retry_at, record_key, completed_at) WHERE completed_at IS NULL;
-- or C (the MySQL/MariaDB form, non-partial):
CREATE INDEX idx_outbox_record_incomplete_created ON outbox_record (completed_at, created_at, id)
  INCLUDE (partition_no, status, next_retry_at, record_key);
DROP INDEX idx_outbox_record_record_key_created ON outbox_record;   -- prefix duplicate
```
F and C give the same hot-path times. Differences: F is 3.5 MB vs C 18 MB (200k rows), and F's smaller index is cheaper to maintain, but only C also serves the cleanup selects (below). `completed_at` must be in F's INCLUDE list, otherwise the plan does a clustered-index lookup per outer row just to re-check the filter column (1.4 ms vs 1.0 ms here; more when many rows are skipped). Choose C if the cleanup select matters (130 -> 11 ms; ids 17.5 -> 0.8 ms), F otherwise. Keep `record_key_completed_created` (the probe), `status_retry`, `status`, `partition_status_retry`. Dropping `record_key_created` caused no regression (probe and pages use `record_key_completed_created`).

## Query forms (T-SQL)
- Keys (with filter) and oldest due: the H60 head-of-key form, `SELECT TOP (:batch) o.record_key ... AND o.completed_at IS NULL AND NOT EXISTS (probe) ORDER BY o.created_at, o.id`. `TOP (n)` and `ORDER BY ... OFFSET 0 ROWS FETCH NEXT n ROWS ONLY` produce the same plan and time. Row goal works: the ordered scan of the new index reads about 370 index rows for 100 keys (plan: Top <- Nested Loops anti semi join (ordered prefetch) <- ordered index scan/seek on the new index; probe = seek on `record_key_completed_created`), logical reads 4,014 -> 380. Oldest due: `SELECT (SELECT TOP (1) o.next_retry_at ... ORDER BY o.next_retry_at)`.
- Keys without the previous-failure filter (`earlier.status='NEW' AND earlier.next_retry_at <= now` probe): the optimizer picks a parallel hash anti join over the new index (no early stop): 38 ms in B, 70 ms in A, versus 18.7 / 1.6 ms for the current GROUP BY query. Fix: index hint on the probe, `FROM outbox_record earlier WITH (INDEX(idx_outbox_record_record_key_completed_created))` -> 1.2 ms (B), 9 ms (A, where the whole set is returned anyway). `OPTION (LOOP JOIN)` and `MAXDOP 1` are unreliable (880 ms, 43 ms). Multi-column statistics (`status, completed_at`, with and without `next_retry_at`) did not change the plan. This variant is not the default path (`stopOnFirstFailure` is true), so it can also keep the current query.
- Pages: IN form with `status='NEW' AND completed_at IS NULL ORDER BY record_key, completed_at, created_at, id` (same rows, same order as current). CROSS APPLY over `VALUES` is not recommended: it ran about as fast as IN (not faster) and, on this build, returned wrong results (below).
- Oldest created: add `AND completed_at IS NULL` (46 -> 0.2 ms in B; with the new index; `TOP (1) ... ORDER BY created_at` is the same 0.2 ms).
- Cleanup: `SELECT TOP (:limit) ... ORDER BY completed_at`.

## Hot path, before -> after (ms)
| query | A base -> F / C | B base -> F / C |
|---|---|---|
| keys, filter on (TOP 100) | 2.9 -> 2.5 / 2.5 | 38.6 -> 1.0 / 1.0 |
| keys, filter on, parameterized (sp_executesql, 64+3 params) | 5.9 -> 2.2 / 0.8 | 40.8 -> 0.8 / 0.8 |
| keys, filter off (probe hint) | 1.6 -> 8.7 / 10.6 | 18.8 -> 1.2 / 1.2 |
| oldest due (filter on) | 2.5 -> 1.8 / 1.4 | 38.0 -> 0.4 / 0.4 |
| oldest created (+ completed_at IS NULL) | 1.2 -> 0.2 / 0.2 | 46.1 -> 0.2 / 0.2 |
| pages 100 keys (IN) | 9.0 -> 4.9 / 4.9 | 124 -> 11.4 / 10.6 |
| pages 1000 keys (IN) | 65 -> 73 / 69 | 220 (noisy: 101-220) -> 134 / 130 |
| cleanup select (TOP 1000) | 195 -> 122 / 9.8 | 219 -> 122 / 11.4 |
| cleanup ids (TOP 1000) | 28 -> 18 / 1.0 | 27 -> 17.5 / 0.8 |
| failed before (TOP 1000) | 0.6 -> 0.4 | 0.6 -> 0.4 |
A is already cheap at 200k rows (about 100 owned due keys, all returned), so only B discriminates. Pages with 1000 keys return 8,000 wide rows in B, so it is dominated by result transfer; the 100-key case shows the real win (the current plan scans the full ordering index).

## Answers to the specific questions
- Filtered vs non-partial: equal on the hot path. F is smaller (3.5 vs 18 MB); C also covers cleanup. Status-leading form not tested (C already wins).
- Filtered index under parameters: with a literal `completed_at IS NULL` in the query text, `sp_executesql` plans (67 parameters) use the filtered index (B-F plan `plans/B-F-new_keys_f_top100_param.txt`, 380 logical reads, same plan and time as literals). Only `completed_at = @p`-style predicates would not match.
- Row goal: yes, for TOP and OFFSET/FETCH alike, in the filter-on form.
- IN-list limits: 2,100 parameters per request. 64..256 partitions + 3 + 1000 keys is fine (about 1,260 max); 1000-key IN via sp_executesql worked; ~2,100+ keys fails (error 180 in my test). Current batch limits (<= 1000 keys) are safe. Also note SQL Server's max 1000 rows per `VALUES` constructor.
- Multi-column statistics: only tried on the filter-off variant; it did not change the (bad) plan, a hint did. Filter-on plans needed none.

## Equivalence
Full ordered result without TOP, same data: key sets identical (A 101 / 107 keys, B 1,201 / 1,207), order equal up to `created_at` ties (the created_at sequence matches; the current ORDER BY MIN(created_at) is nondeterministic inside ties), oldest-due and oldest-created values equal, pages IN identical to current (byte-identical row hash). Filter-off probe-hinted form: same set as current.

## Blockers / caveats
- **CROSS APPLY (VALUES (...)) wrong result on this build (16.0.4295)**: with 299 or more literal key rows, the plan (Constant Scan -> Index Seek on `record_key_completed_created` -> clustered lookup) silently drops the rows of exactly the 298th key of the list (reproduced for ascending, reversed and offset lists; `COUNT(*)` over the same query is correct because it uses a different plan; `MAXDOP 1`, `LOOP JOIN`, `RECOMPILE`, `FORCE ORDER` do not change it). Not investigated further; use the IN form on SQL Server.
- Filtered index needs `QUOTED_IDENTIFIER ON` (and related SET options) in the writing sessions; the JDBC driver and the DDL script via sqlcmd -I satisfy this, plain `sqlcmd` does not.
- The current DDL (`outbox-tables.sql`) creates fine as-is.
- Filter-off query needs the probe index hint (above) or keeps the current form.

## Write cost (B, 50k inserts / updates to COMPLETED / deletes, ms; two runs, noisy)
base 2159, 1993 / 1787, 2201 / 1206, 1579; F 1721, 1947 / 2541, 2280 / 1688, 1252; C 1914, 1934 / 2803, 2709 / 1463, 1994 (`phase3/sqlserver/write.log`). Roughly neutral for F; C is up to about +25% on the status update (the key leaves the NULL range).
