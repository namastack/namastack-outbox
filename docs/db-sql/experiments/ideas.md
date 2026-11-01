# PostgreSQL experiment ideas (proposals, nothing here is measured)

Sources: `01-usage-map.md`, `01-usage-map-v1-delta.md`, `02-scenarios.md`, `STATE.md`, S1/S2 plans+timings of main and v1 (partial), and the code in
`outbox-wt-main` / `outbox-wt-v1` (DDL `schema/postgres/outbox-tables.sql`, `JdbcOutboxRecordQueries`). Query ids are those of `02-scenarios.md`.
Base: `M` = main, `V` = v1. "DDL" = a `--ddl` file (full v1/main DDL minus the dropped indexes plus the new ones). "SQL" = `--queries <dir>/<id>.sql` override.

> **Orchestrator note (user decision 2026-11-01, see STATE.md):** priorities are P1 hot path > P2 new metrics > P3 retention/admin.
> Retention indexes (E06) are dropped from the plan: P3 queries need no index. Drop-index ablations (E05) and active-only partial
> indexes that shrink write cost (E03) move up. Every DDL experiment reports WAL bytes per write.

## Observations that drive the list

1. v1 `keys_strict` outer scan is `idx_outbox_record_created_id_incomplete` (created_at,id) walked in created_at order, filtering
   `partition_no/status/next_retry_at` per entry (Index Only Scan, Heap Fetches > 0 because the visibility map is dirty). Work is proportional to the number of
   *older incomplete rows that are not eligible*: FAILED (S7: 390k), future retries (S6: 195k), rows of unowned partitions (S8), key-blocked heads (S2/S5).
   A `status`-leading index removes the FAILED part and the NEW/FAILED mixing; it cannot remove future retries.
2. v1 partial index predicate `completed_at IS NULL` is a literal in the query text, so it is generic-plan safe. A predicate such as `status = 'NEW'` is **not**
   provable from `status = $1` once pgjdbc switches to a generic plan (after 5 executions, see STATE.md caveat). Every `WHERE status='NEW'` partial index idea
   needs either the literal inlined in the SQL (code change, NEW is the only status the hot path passes) or `plan_cache_mode=force_custom_plan` to be fair.
   Prefer `completed_at IS NULL` predicates; the repository invariant `completed_at IS NULL <=> NEW/FAILED` lets counts/monitors add that literal conjunct.
3. 98 % of rows are COMPLETED and sit in every full index: `idx_status`, `idx_status_retry`, `idx_partition_status_retry`, v1 `idx_record_key_completed_created_id`
   (4 of 5-6 indexes hold mostly dead weight). Partial versions shrink insert/update cost, vacuum, cache footprint and make counts O(active).
4. HOT: `updateProcessingState` changes status, completed_at, next_retry_at, failure_count, failure_reason. PG blocks HOT when any changed column is an index key,
   an INCLUDE column or referenced in an index predicate. Completion (status+completed_at change) can never be HOT while `status`/`completed_at` appear in any index
   (needed by the hot path). Only retry updates (`next_retry_at` only) could be HOT, and only if no index key/INCLUDE/predicate mentions `next_retry_at`
   (v1 INCLUDEs it; main indexes it). Retries are ~10 % of updates, so expect little.
5. PG here is 18 (plans show `Index Searches`): skip scan exists; do not rely on it in comparisons with older PG.

## Priority (run first = top 8)

| Rank | Id | One-liner | Base |
|---|---|---|---|
| 1 | E01 | `(status, created_at, id)` index on main, in two forms (index alone / index + v1 SQL) | M |
| 2 | E02 | v1 with status-leading queue index replacing the created_at-leading partial one | V |
| 3 | E03 | active-only partial indexes replacing `idx_status`, `idx_status_retry`, `idx_partition_status_retry` (+ `AND completed_at IS NULL` in count/monitor SQL) | M, V |
| 4 | E04 | partial record-key index `(record_key, created_at, id) WHERE completed_at IS NULL` replacing v1's full `(record_key, completed_at, created_at, id)`; pages/predecessor forms | V |
| 5 | E05 | cheap drop-index ablations (A1..A6 below) | M, V |
| 6 | E06 | retention indexes: `(completed_at) WHERE completed_at IS NOT NULL` and FAILED partial | M, V |
| 7 | E07 | per-table autovacuum (+ optional fillfactor) for visibility map / bloat | M, V |
| 8 | E08 | key selection with `LATERAL` per owned partition (S6, S7, S8 candidate, S4 risk) | V |

Lower priority (run after the top 8): E09 literal-status partial queue index, E10 v1 queue index without INCLUDE, E11 row-value predecessor check, E12 window/DISTINCT ON head
selection, E13 count strategies, E14 fillfactor/HOT study, E15 pages query forms.

Common correctness checks for **every** experiment: run `bin/bench equiv` against the base (keys ordered by `(created_at,id)`, pages per key, counts, MIN values),
check S5 (FAILED head and future-retry head must block the key in strict mode and *stay in pages* for the non-FAILED rows of the key), S8 (only owned partitions),
and that no index/predicate relies on a status other than the `completed_at IS NULL <=> NEW/FAILED` invariant. Pages must still return future-retry NEW rows.

---

## E01: `(status, created_at, id)` on main (mandatory)

- **Hypothesis**: status-leading, created_at-ordered index lets `oldest_created` and `oldest_due_*` become an ordered index walk (first match) instead of reading every NEW row;
  with v1 query text it also serves `keys_strict` without the FAILED/COMPLETED noise. Main's own `keys_strict` (GROUP BY MIN) still must see all due rows, so the index alone
  should help only oldest_* ; the v1 SQL text is needed for keys.
- **Base**: M. Two variants to separate index from SQL:
  - **E01a** (index only, main SQL): DDL = main DDL +
    ```sql
    CREATE INDEX IF NOT EXISTS idx_outbox_record_status_created_id
        ON outbox_record (status, created_at, id) INCLUDE (partition_no, next_retry_at, record_key);
    ```
  - **E01b** (index + v1 SQL text for `keys_strict`, `keys_nonstrict`, `oldest_created`, `oldest_due_strict`; the other main queries unchanged): same DDL, `--queries` = v1 catalog files for those four ids (they do not require v1 indexes for correctness).
  - **E01c** (E01b with the covering columns removed, plain `(status, created_at, id)`) to price the INCLUDE.
- **Queries**: keys_strict, keys_nonstrict, oldest_created, oldest_due_strict/nonstrict. Also `find_pending_all` (ORDER BY created_at,id, status = NEW) gets an ordered path.
- **Scenarios**: S2, S4 (backlog, early termination), S5, S7 (FAILED noise gone), S6 (future retries still scanned, expected no gain), S8 (64/256 partitions filtered in index).
- **Write cost**: +1 index with 3-6 columns on a 98 %-COMPLETED table: insert +1 tuple; every completion/retry/fail rewrites an entry (status changes => non-HOT already, so no new HOT loss). Full index keeps COMPLETED entries (index bloat ~ 1M entries). Expect insert +5-10 %, `upd_complete` +5-10 %. Check `sizes-*.txt`, `writecost.csv`.
- **Correctness risk**: none for results (read path only); ordering by `(created_at,id)` is exactly the index order. Generic plans fine (no partial predicate).
- **Portability**: MySQL/MariaDB `(status, created_at, id)` (no INCLUDE; InnoDB appends PK id anyway; second-precision TIMESTAMP makes `id` the tie-break); SQL Server `(status, created_at, id) INCLUDE (...)`; Oracle v1 already has `(status, created_at, id, ...)` which is exactly this idea, so E01/E02 result on PG is the evidence for Oracle; H2: composite index without INCLUDE.

## E02: v1 with a status-leading queue index (mandatory)

- **Hypothesis**: replacing the `created_at`-leading partial index by one that leads with `status` (equality) makes `status = :status` an index-range boundary, so FAILED rows (S7) are never visited by the NEW key query and `count`-like predicates get selectivity; Oracle's v1 index already has this shape.
- **Base**: V. DDL = v1 DDL **minus** `idx_outbox_record_created_id_incomplete`, plus one of:
  - **E02a** (replaces it, non-partial):
    ```sql
    CREATE INDEX IF NOT EXISTS idx_outbox_record_status_created_id
        ON outbox_record (status, created_at, id) INCLUDE (partition_no, next_retry_at, record_key);
    ```
  - **E02b** (replaces it, partial, generic-plan safe, small):
    ```sql
    CREATE INDEX IF NOT EXISTS idx_outbox_record_status_created_id_incomplete
        ON outbox_record (status, created_at, id) INCLUDE (partition_no, next_retry_at, record_key)
        WHERE completed_at IS NULL;
    ```
  - **E02c** (adds, keeps the v1 index; planner chooses): E02b without removing the v1 index (to see whether the planner picks it and what the extra index costs).
- **SQL**: v1 SQL unchanged (v1 `keys_strict` already has `o.completed_at IS NULL`, so E02b is usable; `keys_nonstrict`, `oldest_created`, `oldest_due_strict` too).
- **Queries**: keys_strict, keys_nonstrict, oldest_created, oldest_due_strict.
- **Scenarios**: S7 (expected win), S2, S4 (must not regress: early termination), S5, S6 (no gain expected), S8, S10.
- **Write cost**: E02b the same entry count as v1's partial index (only incomplete rows) with 1 extra key column (status is already INCLUDEd in v1, so no extra width); completion removes the row from the index (no new entry). E02a adds COMPLETED entries: insert/update cost +1 full-table index. Expect E02b ~ v1, E02a worse.
- **Correctness risk**: none for results. E02b requires the `completed_at IS NULL` literal in every query that should use it (true for v1 hot queries; `count_*`, `oldest_due_nonstrict` do not have it).
- **Portability**: MSSQL filtered index `(status, created_at, id) INCLUDE (...) WHERE completed_at IS NULL` (filtered index needs literal predicate, parameter sniffing issues possible); MySQL/MariaDB/H2: `(completed_at, status, created_at, id, ...)` is the nearest shape (no partial) but loses range order after `completed_at IS NULL` equality only if `status` equality follows: `(completed_at, status, created_at, id)` still gives ordered output since both leading columns are equality; test in a later dialect round. Oracle: unchanged (already status-leading).

## E03: active-only partial indexes replacing the three status/partition indexes

- **Hypothesis**: `idx_status`, `idx_status_retry`, `idx_partition_status_retry` hold ~98 % COMPLETED entries. Partial `WHERE completed_at IS NULL` versions make counts, `count_partitions`, `oldest_due_nonstrict`, FAILED retention and the update/insert path O(active rows) instead of O(table). `count_new`/`count_failed` become index-only on ~15k entries.
- **Base**: V and M (apply to both). DDL:
  ```sql
  DROP INDEX idx_outbox_record_status;                    -- E03 replaces it (count_*, count_statuses)
  DROP INDEX idx_outbox_record_status_retry;              -- replaced by the next index
  DROP INDEX idx_outbox_record_partition_status_retry;
  CREATE INDEX idx_outbox_record_active_status_retry
      ON outbox_record (status, next_retry_at) WHERE completed_at IS NULL;
  CREATE INDEX idx_outbox_record_active_partition_status_retry
      ON outbox_record (partition_no, status, next_retry_at) WHERE completed_at IS NULL;
  ```
- **SQL overrides** (add the literal conjunct; invariant makes it result-neutral):
  - `count_new/count_failed/count_completed`: only `count_new`, `count_failed` get `AND completed_at IS NULL`. `count_completed` is expected to stay slow (use E13).
    `SELECT COUNT(*) FROM outbox_record WHERE status = :status AND completed_at IS NULL`
  - `count_statuses`: `SELECT status, COUNT(*) FROM outbox_record WHERE status IN (:statuses) AND completed_at IS NULL GROUP BY status` (only valid for NEW/FAILED sets; the COMPLETED variant keeps the original text).
  - `count_partitions`: `... WHERE partition_no IN (:partitions) AND status = :status AND completed_at IS NULL`
  - `count_partition_single`: same with `partition_no = :partitionNo`.
  - `oldest_due_nonstrict`: `SELECT MIN(next_retry_at) FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status AND next_retry_at <= :now AND completed_at IS NULL`.
  - `find_failed_before`, `ids_failed_before`: add `AND completed_at IS NULL`.
- **Queries**: count_new, count_failed, count_statuses, count_partitions, count_partition_single, oldest_due_*, find_failed_before, ids_failed_before, delete_by_status_failed, insert, upd_*.
- **Scenarios**: S2 (count_completed unaffected, count_new/failed), S2L, S7 (FAILED retention), S9 (index bloat/ vacuum), S10, S1 (confirm no regression at small size), writecost.
- **Write cost**: insert: 3 partial entries instead of 3 full ones, but insert of NEW rows still adds 2 tuples (the same count; indexes are smaller on disk). Completion: removes from 2 partial indexes, no new entries (saves 3 new index tuples vs main: completion currently writes new entries in all). Expect `upd_complete` -20-40 %, insert ~ -5 %, smaller vacuum work. `upd_retry`: 2 new entries (as before).
- **Correctness risk**: COMPLETED rows are no longer reachable by those indexes: `count_completed` and `delete_by_status` on COMPLETED fall back to seq scan / other index (measure; paired with E06). Any query lacking the literal conjunct cannot use these indexes (silent seq scan): check plans for every query id. Invariant `completed_at IS NULL <=> NEW/FAILED` is the proof obligation (the repository writes both together in `updateProcessingState`; `updateProcessingState` KDoc says COMPLETED sets completed_at).
- **Portability**: SQL Server filtered indexes (same predicate; needs literal in SQL, ok); Oracle/MySQL/MariaDB/H2 have no partial index: Oracle can emulate with a function-based index on `CASE WHEN completed_at IS NULL THEN status END` (queries must repeat the expression); MySQL 8 functional index likewise with the same expression requirement; H2 none. So gains do not carry to those dialects, only write-cost-neutral variants (dropping the redundant `idx_status`, see A1) do.

## E04: partial record-key index for predecessor check and pages

- **Hypothesis**: v1's `(record_key, completed_at, created_at, id)` covers all 1M rows although both consumers (predecessor `NOT EXISTS` and pages) only need `completed_at IS NULL` rows. A partial `(record_key, created_at, id) WHERE completed_at IS NULL` is ~1.5 % of the size, lets completion *delete* instead of *rewrite* an entry, and gives pages an index-ordered scan without `completed_at` in the sort.
- **Base**: V. DDL = v1 minus `idx_outbox_record_record_key_completed_created_id`, plus:
  ```sql
  CREATE INDEX idx_outbox_record_key_created_id_incomplete
      ON outbox_record (record_key, created_at, id) WHERE completed_at IS NULL;
  ```
- **SQL**:
  - `keys_strict`: v1 text unchanged (`earlier.completed_at IS NULL` literal is in the text and provable).
  - `pages` (**E04a**): keep the LATERAL but drop the constant sort column:
    `SELECT o.* FROM (SELECT t.k FROM unnest(CAST(ARRAY[:recordKeys] AS varchar[])) AS t(k) ORDER BY t.k) k CROSS JOIN LATERAL (SELECT r.* FROM outbox_record r WHERE r.record_key = k.k AND r.status = :status AND r.completed_at IS NULL ORDER BY r.created_at, r.id) o`
  - `pages` (**E04b**, portable-shaped): `SELECT * FROM outbox_record WHERE record_key IN (:recordKeys) AND status = :status AND completed_at IS NULL ORDER BY record_key, created_at, id` (planner should do an ordered ScalarArrayOp index scan, no Sort).
  - `find_incomplete_by_key`: `... WHERE record_key = :recordKey AND status = :status AND completed_at IS NULL ORDER BY created_at, id`.
  - `delete_by_key_status` (RET/ADMIN): add nothing; it will lose its index (v1 already removed `(record_key, created_at)`), measure the seq-scan cost for COMPLETED status; if bad, keep the key index as a mandatory exclusion (see A4).
- **Queries**: keys_strict, keys_nonstrict (`earlier` probe), oldest_due_strict, pages, find_incomplete_by_key, delete_by_key_status.
- **Scenarios**: S2, S4 (1M NEW heads -> big anti-join), S5 (2 000 NEW per hot key: pages cost and ordering), S10, S9.
- **Write cost**: removes an index that every insert and every complete/fail updated (record_key key unchanged but completed_at changes => v1 rewrites its entry on completion). Expect insert -10 %, `upd_complete` -15-25 %, large size reduction.
- **Correctness risk**: strict ordering depends on this index only for speed, not result. Pages must keep returning NEW rows with future `next_retry_at`: yes (no `next_retry_at` filter). `delete_by_key_status` for COMPLETED loses index (retention semantics unchanged, performance only). JPA `OutboxRecordEntity` annotation would need the same change (partial indexes cannot be expressed in `@Index`; document as DDL-only, a portability cost for JPA schema generation).
- **Portability**: MSSQL filtered index same; MySQL/MariaDB/Oracle/H2 keep `(record_key, completed_at, created_at, id)` (IS NULL is part of the key, equality). E04b's `IN (...) ORDER BY record_key, created_at, id` form is portable; the LATERAL is PG-only.

## E05: cheap "drop index" ablations (write-side payoff)

Measure each as its own variant with `writecost`, S9 `churn`, S10, and the read queries listed. All via DDL only (no SQL change unless noted).

| Id | Base | Drop | Reads at risk | Hypothesis |
|---|---|---|---|---|
| A1 | M, V | `idx_outbox_record_status` | `count_new/failed/completed`, `count_statuses`, `delete_by_status_failed` fall to `(status, next_retry_at)` prefix (same entries + 8 bytes); `idx_status` is a strict prefix of `idx_status_retry`, so it is pure write overhead (the planner may prefer the smaller index for count_completed: check) | free write win (one index tuple less per complete/insert) |
| A2 | M, V | `idx_outbox_record_status_retry` (keep `idx_status`) | `find_failed_before`, `ids_failed_before` lose ordered range; count unaffected | trade-off, only if A1 is not enough |
| A3 | M | `idx_outbox_record_record_key_created` (as v1 does) | find_incomplete_by_key, delete_by_key_status, pages (main SQL) | isolates V1-1a on main DDL |
| A4 | V | `idx_outbox_record_record_key_completed_created_id` | predecessor probe, pages, delete_by_key_status (seq scan) | upper bound on what E04 can win, expected catastrophic for keys_strict (confirms necessity) |
| A5 | V | `idx_outbox_record_partition_status_retry` | `count_partitions`, `count_partition_single`, `oldest_due_nonstrict`: lose their index (the v1 queue index only gets `status`/`partition_no` as INCLUDE, not as a key; may still use it index-only with filter) | v1 keys no longer need it: count_partitions may stay OK through the INCLUDE-covering queue index |
| A6 | V | all of A1+A5 together | counts, FAILED retention | the minimum set for hot path + counts |
| A7 | M | main set reduced to `(status, created_at, id)` [E01] + `(record_key, completed_at, created_at)` + `(partition_no, status, next_retry_at)`, dropping `idx_status`, `idx_status_retry`, `idx_record_key_created` | as above | realistic "main minus waste" baseline for comparing v1's added index |

- **Write cost**: each dropped full index removes one tuple per insert and (for status/next_retry_at/completed_at indexes) one per completion; expect 5-10 % per index on insert/`upd_complete`, plus smaller autovacuum and index size.
- **Correctness risk**: none for results; performance cliffs on monitor/retention/admin queries (seq scans on 1M-10M rows) must be reported per query.
- **Portability**: A1 (`(status)` as prefix of `(status, next_retry_at)`) is valid on all dialects; A3/A4 depend on dialect predecessor strategy; MySQL InnoDB secondary indexes append PK so drops save more there.

## E06: retention indexes

- **Hypothesis**: `find_completed_before` / `ids_completed_before` (`status = COMPLETED AND completed_at < :cutoff ORDER BY completed_at LIMIT 1000`) currently have no index (usage map 3.x). A small ordered index makes the retention job O(limit). Completed rows are appended in `completed_at` order, so inserts into this index are right-edge appends (cheap).
- **Base**: V and M (same SQL text for these queries except LIMIT).
- **DDL** (alternatives):
  - **E06a**: `CREATE INDEX idx_outbox_record_completed_at ON outbox_record (completed_at) WHERE completed_at IS NOT NULL;` with SQL `... WHERE status = :status AND completed_at < :cutoff ORDER BY completed_at LIMIT :limit` (`completed_at < $` already implies `IS NOT NULL`, generic-plan safe; `status` becomes a heap filter that is true for every row by the invariant, but the planner does not know: add `AND completed_at IS NOT NULL` if estimates are off).
  - **E06b**: `CREATE INDEX idx_outbox_record_status_completed ON outbox_record (status, completed_at);` (full, no SQL change, status stays in the key).
  - **E06c** (FAILED side): `CREATE INDEX idx_outbox_record_failed_retry ON outbox_record (next_retry_at) WHERE completed_at IS NULL AND status = 'FAILED';` (literal predicate; SQL inlines `'FAILED'` for `find_failed_before`, `ids_failed_before`, `delete_by_status_failed`), replaces `idx_status_retry` for retention while E03 covers counts.
- **Queries**: find_completed_before, ids_completed_before, delete_by_ids, find_failed_before, ids_failed_before, delete_by_status_failed, count_completed (E06b only: ordered index on `status`).
- **Scenarios**: S2 (986k completed), S2L, S7, S9 (retention deletes of the oldest COMPLETED in churn), S3.
- **Write cost**: E06a/b: +1 tuple per completion (append-mostly, hot right edge, low contention); no write for NEW/FAILED rows (E06a). Expect `upd_complete` +3-6 %. Net with E03/E04 savings may be negative overall.
- **Correctness risk**: retention is time-based on `completed_at < cutoff`; the partial predicate relies on `completed_at IS NOT NULL` for COMPLETED only (invariant). `findByStatusAndCompletedBefore` order by `completed_at` (ties arbitrary) unchanged.
- **Portability**: E06b is portable everywhere (`(status, completed_at)`); partial variants: MSSQL filtered only. MySQL/MariaDB/Oracle/H2 use E06b.

## E07: per-table autovacuum and visibility map (S9, S10)

- **Hypothesis**: v1's hot index-only scans show `Heap Fetches` (VM bits cleared by updates). Aggressive per-table vacuum/analyze keeps the VM set and dead tuples low; insert-triggered vacuum (PG13+) sets VM on append-only COMPLETED pages. Also prevents bloat from the non-HOT update on every row.
- **Base**: V and M. DDL (append to the table DDL, run after `CREATE TABLE`):
  ```sql
  ALTER TABLE outbox_record SET (
      autovacuum_vacuum_scale_factor = 0.01,
      autovacuum_vacuum_insert_scale_factor = 0.02,
      autovacuum_analyze_scale_factor = 0.02,
      autovacuum_vacuum_cost_delay = 2,
      autovacuum_vacuum_cost_limit = 1000);
  ```
  Variants **E07b**: `fillfactor = 85` (with the same settings), see E14.
- **Queries**: keys_strict (Heap Fetches / buffers), pages, count_new/failed, oldest_*; S9 `n_dead_tup`, sizes; S10 p95 of keys and pages while vacuum competes for IO.
- **Scenarios**: S9 (mandatory, no manual VACUUM), S10, S2 timings after a churn.
- **Write cost**: more vacuum IO and CPU; foreground writes may see WAL/IO contention (watch S10 p95 insert). Fillfactor < 100 enlarges the heap by 1/ff.
- **Correctness risk**: none.
- **Portability**: PG only; analogs (MySQL purge, SQL Server stats, Oracle stats) not comparable.

## E08: key selection via `LATERAL` per owned partition

- **Hypothesis**: for sparse due sets (S6 195k future retries, S7 390k FAILED, S8 partial ownership) walking `created_at` order wastes work; per-partition probes `(partition_no, status, next_retry_at)` range-scan only *due* rows, then each partition returns its top `:limit` heads and the outer query merges. Cost ~ partitions x (limit + probe). Risk: S4 (1M due) where the created_at walk stops after ~10 rows.
- **Base**: V (needs v1 `NOT EXISTS` text and the predecessor index).
- **DDL**: needs ordering by `created_at,id` within the partition: add
  ```sql
  CREATE INDEX idx_outbox_record_part_status_created_id
      ON outbox_record (partition_no, status, created_at, id) INCLUDE (next_retry_at, record_key)
      WHERE completed_at IS NULL;
  ```
  (no sort and early stop per partition; the future-retry waste moves inside each partition, so E08b below uses the existing `(partition_no, status, next_retry_at)` and sorts).
- **SQL** (`keys_strict`, E08a, `:partitions` expanded as for `pages`; the harness needs the `unnest(CAST(ARRAY[...] AS int[]))` form like `pages`):
  ```sql
  SELECT c.record_key FROM unnest(CAST(ARRAY[:partitions] AS int[])) AS p(no)
  CROSS JOIN LATERAL (
     SELECT o.record_key, o.created_at, o.id FROM outbox_record o
     WHERE o.partition_no = p.no AND o.status = :status AND o.completed_at IS NULL AND o.next_retry_at <= :now
       AND NOT EXISTS (SELECT 1 FROM outbox_record earlier WHERE earlier.record_key = o.record_key
            AND earlier.completed_at IS NULL AND earlier.created_at <= o.created_at
            AND (earlier.created_at < o.created_at OR (earlier.created_at = o.created_at AND earlier.id < o.id)))
     ORDER BY o.created_at, o.id LIMIT :limit) c
  ORDER BY c.created_at, c.id LIMIT :limit
  ```
  **E08b**: same but index `(partition_no, status, next_retry_at)` (E03 partial) and no `part_status_created_id` index (per-partition top-N sort).
  A cheaper hybrid (**E08c**): per-partition `LIMIT 1` oldest head, then global order, if `limit` is small, is not a faithful equivalent (returns <= 1 key per partition): do not run.
- **Queries**: keys_strict (and keys_nonstrict analog, drop NOT EXISTS conditions to the nonstrict text).
- **Scenarios**: S6, S7, S8 (64 partitions: 4x fewer probes), S2, S4 (expect regression), S5, S10 (planning/exec of 256 probes at 0.5/s per instance).
- **Write cost**: E08a +1 partial index (all inserts of NEW + completion removal); E08b none beyond E03.
- **Correctness risk**: global order by `(created_at, id)` of heads preserved only because per-partition `LIMIT :limit` returns each partition's oldest `:limit` eligible heads, so the global top `:limit` is a subset. A key lives in exactly one partition (`PartitionHasher`), so no duplicates. Result must equal v1's (`equiv`). Partition ownership: uses the owned list only.
- **Portability**: PG only (LATERAL exists on MySQL 8.0.14+, Oracle 12c+ CROSS APPLY, SQL Server `CROSS APPLY`, but needs a number table / JSON array expansion for `:partitions`; not worth it unless E08 wins by a lot).

---

## Lower priority

### E09: literal-status partial queue index (v1 base)
`CREATE INDEX ... ON outbox_record (created_at, id) INCLUDE (partition_no, next_retry_at, record_key) WHERE completed_at IS NULL AND status = 'NEW';` + SQL `keys_strict`/`keys_nonstrict`/`oldest_created` with `'NEW'` inlined instead of `:status` (keep `:status` binding path unchanged for FAILED users). Hypothesis: smaller than v1's index (no FAILED, 390k less in S7), no status/INCLUDE column needed (`status` dropped from INCLUDE, `next_retry_at` retained), which unblocks HOT on `status` only partially. Run with `plan_cache_mode` default and `force_custom_plan` to show the generic-plan trap. Write cost: smaller than v1. Risk: if code ever calls the query with another status it cannot use the index (correct, slower). Portability: MSSQL filtered; others no.

### E10: v1 queue index without INCLUDE
`CREATE INDEX ... ON outbox_record (created_at, id) WHERE completed_at IS NULL;` (drop INCLUDE). Prices the INCLUDE: index-only vs heap fetch. INCLUDE'd `next_retry_at`/`status` block HOT for retries and widen the entry; measure keys_strict at S2/S4 and `upd_retry`, `upd_complete` writecost.

### E11: row-value predecessor check (v1 base, needs E04 index)
Replace `earlier.created_at <= o.created_at AND (earlier.created_at < o.created_at OR (earlier.created_at = o.created_at AND earlier.id < o.id))` with `(earlier.created_at, earlier.id) < (o.created_at, o.id)` so the whole condition is one index boundary on `(record_key, created_at, id)`. Applies to `keys_strict`, `oldest_due_strict`; nonstrict adds the `status`/`next_retry_at` filters. PG-only (MSSQL has no row-value comparison; Oracle supports only equality; MySQL/MariaDB/H2 support but optimizer may not use range).

### E12: window / DISTINCT ON head selection (v1 base)
`SELECT record_key FROM (SELECT record_key, status, next_retry_at, partition_no, created_at, id, row_number() OVER (PARTITION BY record_key ORDER BY created_at, id) rn FROM outbox_record WHERE completed_at IS NULL) h WHERE rn = 1 AND status = :status AND next_retry_at <= :now AND partition_no IN (:partitions) ORDER BY created_at, id LIMIT :limit` (and `DISTINCT ON (record_key) ... ORDER BY record_key, created_at, id` wrapped in an outer sort). Both read the whole incomplete set (S4 1M NEW) and cannot stop early, so expected loser except on tiny incomplete sets; run only to document why. Needs E04 index for sorted input. Strict semantics: head of key must be the eligible NEW row; identical to NOT EXISTS (`equiv`). Portable (window functions) except DISTINCT ON.

### E13: count strategies
- **E13a** (`count_completed`): none beyond making the VM clean (E07) so `(status)`/`(status, next_retry_at)` is index-only; baseline S2: 28.6 ms at 1M, S2L ~300 ms expected. Compare with `idx_outbox_record_status_created_id` (wider, slower).
- **E13b**: `count_statuses` default `{NEW,FAILED}` via E03 partial (status, next_retry_at); verify one index-only scan on ~15k entries.
- **E13c**: approximate COMPLETED = `(SELECT reltuples::bigint FROM pg_class WHERE oid = 'outbox_record'::regclass) - (NEW+FAILED)`. Not exact, semantic change of the SPI (documented as approximate only): do not propose for default, record the latency floor.
- **E13d**: counter table maintained by trigger: rejected (hot row contention, 2x write cost) except as a reference upper bound; skip.
- Write cost: none (a/b/c).
- Portability: a/b generic; c PG only.

### E14: fillfactor / HOT study
`ALTER TABLE outbox_record SET (fillfactor = 80);` combined with (i) v1 as is, (ii) v1 minus INCLUDE of `next_retry_at` and without any index mentioning `next_retry_at` (queue index `(created_at, id) WHERE completed_at IS NULL` + `next_retry_at` read from the heap), to see whether `upd_retry` becomes HOT. Completion cannot be HOT (status and completed_at are indexed/predicate). Measure `n_tup_hot_upd` / `n_tup_upd` in `churn.txt`. Expected: HOT ratio ~ retries only (<= 10 % of updates), heap growth 1/ff, so low priority unless S9 shows bloat on the heap rather than indexes.

### E15: pages query forms (v1 base, no DDL change)
- P1 `= ANY`: `... WHERE record_key = ANY(CAST(ARRAY[:recordKeys] AS varchar[])) AND status = :status AND completed_at IS NULL ORDER BY record_key, completed_at, created_at, id` (no LATERAL; compare plan: per-key probe + sort).
- P2 v1 LATERAL with `ORDER BY r.created_at, r.id` (needs E04) and without the `ORDER BY t.k` subquery sort (outer order not part of the contract: pages are grouped by key in memory).
- P3 `JOIN unnest(...) WITH ORDINALITY` to preserve key order for nicer plans.
Scenarios: S4 (batch 100 keys), S5 (2 000 NEW per key), S1. Risk: none for results (grouping in memory, per-key order by `(created_at,id)`); the JPA path cannot use LATERAL, so the portable P1/IN form should be used for JPA comparisons.

## Harness notes

- Each experiment id gets `experiments/<id>/ddl.sql` (full DDL) and `experiments/<id>/queries/<qid>.sql` with the headers inherited from `catalog/<base>/postgres/<qid>.sql`.
- Add `sizes-*.txt`, `writecost.csv` for every E0x (index count/size is the main cost evidence).
- Ratios to compare against: v1 timings (S1 keys_strict 0.72 ms, oldest_created 0.72 ms, count_completed S2 28.6 ms on main) and noise threshold from STATE.md once filled.
