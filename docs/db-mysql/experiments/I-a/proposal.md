# I-a: index proposals for the Q-merged forms (MySQL 8.4, InnoDB, no measurements)

All numbers below are predictions to be checked with `EXPLAIN ANALYZE`. Basis: only the `QM-*.sql` files in `Q-merged/FILES.txt`, usage map 1/2/6, scenarios, ADR-0005 index rule.
Ranking basis: partial ownership (S8: 64/256, S16: 16/256). DDL files: `IA-<n>.ddl.sql` (full `outbox-tables.sql`, only `outbox_record` indexes differ; each starts with a one-line `--` comment).

## Index building blocks (names kept so INDEX/FORCE INDEX hints in the forms resolve)

| Id | Name | Columns (implicit PK `id` is appended by InnoDB where not listed) | Role |
|---|---|---|---|
| K | `idx_outbox_record_key_completed_created_id` | `(record_key, completed_at, created_at, id)` | `e` side of strict anti-join (covering: ref on key, `IS NULL`, range on `created_at`, EXISTS stops at the first hit); `pages` (ref per key, ordered by `created_at,id`, `SELECT *` needs the clustered row anyway) |
| A | `idx_outbox_record_status_completed_created_id` | `(status, completed_at, created_at, id, partition_no, next_retry_at[, record_key])` | `o` side of keys: ref on `status`+`IS NULL`, **ordered** by `(created_at,id)` so `LIMIT` stops early; `partition_no`/`next_retry_at` are ICP/covering filters |
| R | `idx_outbox_record_status_completed_retry` | `(status, completed_at, next_retry_at, partition_no)` | ordered `oldest_due_*` (`ORDER BY next_retry_at LIMIT 1`, range `<= now`, ICP on partition) |
| P | `idx_outbox_record_status_completed_partition` | `(status, completed_at, partition_no)` | counts: one range per owned partition (64 ranges at S8), covering, narrow (~50 B/entry) |

Key facts used: a secondary entry = key columns + `id` (VARCHAR(255), actual length ~37 B for UUIDs); `id` named explicitly in a key is not stored twice, so an explicit trailing `id` is physically identical to the implicit one; an `id` placed *before* further columns is what makes `ORDER BY created_at, id` index-ordered while still covering `partition_no, next_retry_at`.
`status = X` already excludes COMPLETED (`completed_at IS NULL` <=> NEW/FAILED), so `completed_at` in a status-leading index is only there to make the forms' `completed_at IS NULL` index-evaluable (covered). `record_key` in A is only needed for the returned rows and the anti-join probe (a clustered lookup for the few candidates that pass the ICP filters).

## Per-form expected access path (best index, partial ownership)

| Form | Index | Expected plan / rows (S8-S2, S8-S4, S5, S7) |
|---|---|---|
| keys_strict-0 (also -6/-7 hinted fallback) | `o`: A; `e`: K | `o` ref(status,IS NULL) ordered, no filesort; ICP on partition+next_retry. Examined ~40-60 entries / returned 10 (S2, S4: ~25% ownership, 5% blocked). `e` probe per candidate: K ref + range, covering, 1 row. S7: FAILED rows are in a different `status` range, unaffected. **S5 risk**: non-head NEW rows of hot keys pass ICP and are rejected by the anti-join; examined count depends on where heads sit in `created_at` order (up to ~50k entries at 25% ownership). Row-constructor (-2), `id = (subq LIMIT 1)` (-3), window (-4) have no index-ordered path. |
| keys_nonstrict-0 (-7 hinted) | `o`: A; `e`: K | same, but `e` additionally needs `status`/`next_retry_at`: one clustered lookup per `e` candidate (a FAILED or future head is skipped by walking K). K2 (IA-7) filters them from the index. -5/-11 (`GROUP BY record_key ORDER BY MIN`) must read all owned due rows (S4: ~250k) then sort; -4/-12 same. No index fixes this. |
| pages-0 (-1 hinted K) | K | `record_key IN` ranges, `IS NULL`, ordered by `created_at,id` (no filesort expected, verify), clustered lookup per row (`SELECT *`). Rows = rows of the page (S5: ~20k for 10 keys). Risk: optimizer picks A (status ref) when keys are few; -1 pins K. |
| count_partitions-5 (-6 hinted P, -0 without `IS NULL`) | P (else R, else A) | covering range scan, 64 ranges, examined = owned NEW (S4: ~250k, S2: ~300); without P the narrowest covering status index (R, then A) is scanned over all NEW (S4: 1M, `partition_no` only as filter). -7 (VALUES join) needs P for per-partition lookups. |
| count_statuses-4 (-6 hinted, -3 hinted without `IS NULL`) | P (else R/A) | covering tight scan of the NEW and FAILED ranges (no row counts in InnoDB): S2 ~14k, S4 1M, S7 400k; cost is the narrowest covering index. -1/-5 (UNION ALL per status) and -2 equal work. |
| oldest_created-1 (-2 scalar subquery) | A | ordered scan, ICP on partition: stops at first owned entry (S4/S2: <10 entries). The `MIN()` forms (-0, -4) scan the whole `status` range (no group-min-max because of `partition_no IN`). Empty owned partitions degrade to a full status-range scan. |
| oldest_due_nonstrict-1 (-3/-5 hinted) | R | range `next_retry_at <= now`, ordered, ICP partition, first hit: <10 entries. Without R: `-0` MIN over a covering scan of A (all NEW entries). With M (`partition_no` before `next_retry_at`): ranges per partition, no order: `-0`/`-4` read all owned due entries (S4 ~250k). |
| oldest_due_strict-0 (-3; -7 hinted) | `o`: R (+ lookup for `record_key,created_at,id` per candidate unless R2), `e`: K | `ORDER BY next_retry_at LIMIT 1`: ordered scan of R, one anti-join probe per candidate, stops at first unblocked. S5: candidates are mostly heads, few rejects. Without R: filesort of all owned due rows with an anti-join probe each (S4: ~250k probes), or MIN form: same. |

## Index sets

Write cost columns: secondary-index entries touched per operation (clustered row is written in every case). `insert`/`delete_by_id` touch all secondary indexes. `upd_complete` changes `status`+`completed_at`; `upd_retry` changes `next_retry_at`; `upd_fail` changes `status`.

| Set | Indexes | insert / complete / retry / fail / delete | Entry size (approx, UUID id, ~36 char key) |
|---|---|---|---|
| **IA-0** branch | A(+record_key), K, R, P | 4 / 4 / 2 (A,R) / 3 (A,R,P) / 4 | A ~140 B, K ~90 B, R ~60 B, P ~50 B |
| **IA-1** minimal 2 | A2, K | 2 / 2 / 1 (A2) / 1 / 2 | A2 ~100 B, K ~90 B |
| **IA-2** minimal 3 | A2, K, R | 3 / 3 / 2 / 2 / 3 | + R ~60 B |
| **IA-3** merged partition/retry | A2, K, M | 3 / 3 / 2 (A2,M) / 2 / 3 | M ~55 B |
| **IA-4** branch without `record_key` in A | A2, K, R, P | 4 / 4 / 2 / 3 / 4 | A2 saves ~40 B/entry vs IA-0 |
| **IA-5** implicit id | A' `(status,completed_at,created_at)`, K' `(record_key,completed_at,created_at)`, R, P | 4 / 4 / **1** (R only) / 3 / 4 | A' ~50 B |
| **IA-6** id-position control | An `(status,completed_at,created_at,partition_no,next_retry_at)`, K, R, P | 4 / 4 / 2 / 3 / 4 | An ~100 B |
| **IA-7** read-optimal | A(+record_key), K2 `(record_key,completed_at,created_at,id,status,next_retry_at)`, R2 `(status,completed_at,next_retry_at,partition_no,created_at,id,record_key)`, P | 4 / 4 / **3** (K2,A,R2) / **4** (K2,A,R2,P) / 4 | R2 ~140 B, K2 ~100 B |

### Forms per set (one per query; fallback in brackets, used if the plan check shows a wrong index or filesort)

| Query | IA-0 | IA-1 | IA-2 | IA-3 | IA-4 | IA-5 | IA-6 | IA-7 |
|---|---|---|---|---|---|---|---|---|
| keys_strict | 0 [6] | 0 [6] | 0 [6] | 0 [6] | 0 [6] | 0 [6] | 0 (expect filesort) | 0 [6] |
| keys_nonstrict | 0 [7] | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| pages | 0 [1] | 0 [1] | 0 [1] | 0 [1] | 0 [1] | 0 [1] | 0 [1] | 0 [1] |
| count_partitions | 5 [6] | 5 (scans A2) | 5 (scans R) | 5 | 5 [6] | 5 [6] | 5 [6] | 5 [6] |
| count_statuses | 4 [6] | 4 | 4 | 4 | 4 [6] | 4 [6] | 4 | 4 [6] |
| oldest_created | 1 | 1 | 1 | 1 | 1 | 1 | 1 | 1 |
| oldest_due_nonstrict | 1 [5] | 0 (MIN scan of A2) | 1 [5] | 0 or 4 (MIN, all owned due) | 1 [5] | 1 [5] | 1 [5] | 1 [5] |
| oldest_due_strict | 0 [3, 7] | 2 (full scan + probes) | 0 [3, 7] | 2 | 0 [3, 7] | 0 [3, 7] | 0 [3, 7] | 0 [3] |

Hint caveat: forms 6/7 (`keys_strict`), 7 (`keys_nonstrict`), -6 (`count_partitions`) and the `oldest_due_*` hinted forms name indexes. A missing name gives a warning for optimizer hints but an **error** for `FORCE INDEX` (keys_strict-7); do not use those forms in IA-1/IA-2/IA-3 where R and/or P are absent. IA-3's `idx_outbox_record_status_completed_partition` is M (3rd/4th column `partition_no, next_retry_at`).

### Rationale and predicted trade-offs per set

- **IA-0**: reference. Retry updates touch A and R; fail touches three.
- **IA-1**: answers "strict-key subquery index (K) plus one status/`completed_at`-leading index". A2 alone covers ordered keys, `oldest_created`, and (scan-only) counts and `oldest_due`. Cheapest writes (2 indexes; retry touches only A2). Slower P2: counts scan the full NEW/FAILED range through A2 (~100 B entries, S4 1M entries, ~4x the entries of IA-0 at 64/256); `oldest_due_*` read all NEW entries or filesort (S4: strict form probes K ~250k times). Likely acceptable only if P2 backlog scenarios (S4) are not weighted.
- **IA-2**: adds R, so `oldest_due_*` stay ordered; counts use R (narrower than A2, still no partition range). Drops P (saves one entry on insert, complete, fail, delete).
- **IA-3**: counts get the partition ranges (M), `oldest_due_*` lose ordering (reads all owned due entries; strict form filesorts with probes). Predicted worse than IA-2 for `oldest_due_strict` at S4/S5; test to confirm.
- **IA-4**: isolates `record_key` in A. Expect the same plans as IA-0: `record_key` is only fetched by clustered lookup for rows that pass ICP (~10-60 per call); saves ~28% of A bytes and redo.
- **IA-5** (explicit `id` unnecessary?): K' is physically identical to K (expect identical plans, extended-key use). A' makes ordering depend on the implicit `id` suffix (`ORDER BY created_at, id` still index-ordered through `use_index_extensions`, default on) but is not covering: one clustered lookup per examined entry (S4 ~40, S2 ~50, S5 up to thousands) in exchange for removing A from `upd_retry`. Check `EXPLAIN` for filesort and lookups.
- **IA-6** (explicit `id` necessary?): negative control. With `partition_no, next_retry_at` between `created_at` and the implicit `id`, `ORDER BY created_at, id` cannot be satisfied by the index: expect `Using filesort` over all owned due NEW rows (S4 ~250k, each with a K probe before the sort if the anti-join runs first) and loss of `LIMIT` early exit. Demonstrates that explicit `id` matters when columns are appended after `created_at`, and does not when nothing follows.
- **IA-7**: K2 filters `status`/`next_retry_at` in the nonstrict `e` probe without lookups; R2 covers `o.record_key, created_at, id` for the `oldest_due_strict` anti-join (no lookup per candidate). Costs: retry touches 3 indexes, fail touches 4, entries up to ~140 B. Gain only for nonstrict-with-failed-heads and S5-like strict `oldest_due`. Likely not worth it under ADR-0005 unless S5 shows lookups dominating.

Predicted ranking at partial ownership (to verify): read side IA-7 >= IA-0 ~ IA-4 > IA-5 ~ IA-2 > IA-3 > IA-1 >> IA-6; write side IA-1 < IA-2 ~ IA-3 < IA-5 < IA-0 ~ IA-4 ~ IA-6 < IA-7. Best candidate overall: IA-4 or IA-2.

## Schema-change ideas (not in any DDL; need a user decision, ADR-0005 forbids column-type changes)

1. **Generated/virtual column, e.g. `open_created_at AS (IF(completed_at IS NULL, created_at, NULL))`** with an index on it: NULL keys are still indexed, so no partial-index saving; forms would also need the generated column. Not recommended.
2. **Functional indexes** (hidden virtual columns): same limitation, queries must repeat the exact expression.
3. **Physically separate pending table** (NEW/FAILED rows only; move to history or delete on completion): the only real partial-index emulation on InnoDB, but changes the persistence model, ordering/retention semantics and MongoDB/JPA parity. ADR-scale decision.
4. Dropping `completed_at` from status-leading indexes (a pure index change, but forms with `completed_at IS NULL` then need a clustered lookup per passing row; usable only with the forms that omit it: count_partitions-0, count_statuses-0, oldest/keys via `LIMIT`). Not proposed as a set; saves ~5 B/entry only.

Invisible indexes: usable to screen IA-0 minus an index without rebuilding, but the index is still maintained, so write cost is unchanged. Descending key parts: no form needs them.

## MariaDB

All eight DDLs use only plain `INDEX` definitions (no `INVISIBLE`, no `DESC`, no functional parts); they run on MariaDB reached through MySQL Connector/J. Longest key (IA-7 R2/K2) is ~2.2 KB at declared maxima, under the 3072-byte limit. Plans differ (MariaDB has no hash/anti-join like MySQL's; optimizer hints in the forms such as `INDEX(o ...)`/`SKIP_SCAN`/`JOIN_ORDER` are MySQL-specific syntax and are ignored or rejected there), so MariaDB needs its own validation; `FORCE INDEX` forms work on both.
