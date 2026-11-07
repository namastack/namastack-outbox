# Independent proposer: query forms (MySQL 8.4.13, no measurements)

> **Rebased to 270c42cf (2026-11-06).** The index names and columns in this file describe the 2a2663de schema in which the forms were written; the 270c42cf indexes are `idx_outbox_record_status_created_id` (status, created_at, id, partition_no, next_retry_at, record_key), `idx_outbox_record_key_completed_created_id` (unchanged), `idx_outbox_record_status_retry_partition` (status, next_retry_at, partition_no), `idx_outbox_record_status_partition` (status, partition_no). Hints in the `.sql` files were renamed accordingly (no hint lost its target). No new forms were added.
> New reference SQL for keys_strict, keys_nonstrict, oldest_created, oldest_due_strict, oldest_due_nonstrict: see `../Q-merged/<id>/QM-<id>-0.sql`. Duplicate status of this file's forms of those ids, after normalising aliases, whitespace and `LIMIT`:
> - Not byte-identical to the new reference: none (the forms keep the outer `completed_at IS NULL` that the new SQL dropped, or use other shapes).
> - Equal to the new reference apart from the redundant outer `completed_at IS NULL` (plus hints/aliases): QI-keys_strict-5, -6 (hints); QI-keys_nonstrict-6, -7 (hints); QI-oldest_created-2; QI-oldest_due_nonstrict-2; QI-oldest_due_strict-6 (hint)
> - Formerly duplicates of the 2a2663de reference, now duplicates of the former reference kept as `QM-<id>-<n>` (`keys_strict-11`, `keys_nonstrict-13`, `oldest_created-6`, `oldest_due_strict-10`, `oldest_due_nonstrict-6`): QI-oldest_created-1, QI-oldest_due_nonstrict-1 (both MIN forms)

Schema assumed (branch DDL): `idx_outbox_record_status_completed_created_id (status, completed_at, created_at, id, partition_no, next_retry_at, record_key)` = **IX_S**,
`idx_outbox_record_key_completed_created_id (record_key, completed_at, created_at, id)` = **IX_K**,
`idx_outbox_record_status_completed_retry (status, completed_at, next_retry_at, partition_no)` = **IX_R**,
`idx_outbox_record_status_completed_partition (status, completed_at, partition_no)` = **IX_P**. No index is proposed.
`TIMESTAMP` has second precision, so `created_at` ties are common and the `id` tie-break must be kept in every form.

## Runner support needed (do not edit the harness; state only)

Existing: `:partitions`, `:statuses`, `:recordKeys` (expanded to `?,?,...`), scalar `:status`, `:now`, `:limit`; a named scalar may occur several times;
**a collection param (`:partitions`) occurring twice is needed by `count_partitions` -1 and -4** (bind the list twice).
New, per form (forms without these need nothing):

| Token in SQL | Forms | Runner must |
|---|---|---|
| `:recordKeysJson` | pages-3, pages-5 | bind ONE string param: JSON array of the keys, e.g. `["k1","k2"]` (Jackson-escaped) |
| `:statusesJson` | count_statuses-2 | same with the status names |
| `:recordKeysRows` | pages-4 | expand to `ROW(?),ROW(?),...` (one bind per key) |
| `:recordKeysSelects` | pages-7 | expand to `SELECT ? AS k UNION ALL SELECT ? AS k ...` (alias `k` on the first select only is enough; one bind per key) |
| `/*EACH:<coll>:<sep>*/ body /*END*/` | pages-6, count_statuses-1 | repeat `body` once per element of `<coll>`, joined by `<sep>`; in each copy `:item` becomes `?` bound to that element; other named params (`:status`) are bound in every copy |

Unused binds: `oldest_due_nonstrict` forms use a literal `LIMIT 1` and do not reference `:limit` (the runner may still supply it).
Forms `oldest_created` -2 and `oldest_due_*` -2/-4 (bare `ORDER BY ... LIMIT`) return **zero rows** when nothing matches, where `MIN` returns one NULL row;
the `wrapped` forms (-3/-4/-5, `SELECT (SELECT ... LIMIT 1)`) always return exactly one row and are the shape-equivalent replacement. The equivalence check
must treat "no row" and "NULL" the same for the bare forms (repository maps both to `null`).

## General notes

- Key facts used: `completed_at IS NULL` iff NEW/FAILED (usage map 2.1); the "first incomplete record of a key" in `(created_at, id)` order is the only
  non-blocked one in strict mode, so `NOT EXISTS earlier`, `LEFT JOIN ... IS NULL`, `o.id = (first incomplete of key)` and `ROW_NUMBER() = 1` are equivalent.
- Hints in `/*+ ... */` are ignored with a warning by MySQL when a name does not match (hint table/index names) but `INDEX(o idx_x)` on a schema without `idx_x`
  silently loses the index (high risk); `FORCE INDEX (idx_x)` on a missing index is an **error** (high risk). On MariaDB 11/12 `/*+ ... */` comments are
  treated as comments (11) or parsed with its own (partly different) hint set (12: unknown ones warn); `FORCE INDEX` is MariaDB-valid but errors the same way.
- `SET_VAR` is not used (non-default settings are a user decision).
- Hash anti-join risk: when MySQL turns `NOT EXISTS` into a hash antijoin it builds the whole `earlier` side and the outer `LIMIT` cannot stop early;
  forms 3 and 5 (`keys_*`) avoid or steer around that.

## keys_strict (QI-keys_strict-1..7)

Contract: due NEW record without any earlier incomplete record of the same key, ordered `created_at, id`, `LIMIT :limit`, no capping before the anti-join.
Ideal access path: outer IX_S ref `status='NEW', completed_at IS NULL` walked in `(created_at, id)` order (filters `partition_no`, `next_retry_at` in-index) with early stop; probe IX_K `(record_key, completed_at IS NULL, created_at...)` per candidate.

1. **LEFT JOIN ... IS NULL.** Branch predicate (with the redundant `created_at <=` conjunct) as an explicit anti-join. Idea: MySQL 8.0.17+ recognises it as an antijoin (same plan as NOT EXISTS) but sometimes picks different join order/strategy. Path IX_S + IX_K. Risk: none semantically (`e.id` is the PK, never NULL in a match). MariaDB: parses, same rows.
2. **NOT EXISTS with row constructor** `(e.created_at, e.id) < (o.created_at, o.id)`. Idea: one comparison, no repeated `created_at`. Hypothesis: the optimizer expands it to `a<x OR (a=x AND b<y)`, giving two ranges on IX_K instead of the single range created by the `<=` conjunct; may be better or worse. Same path. MariaDB: parses, same rows (row comparison supported).
3. **Scalar "first incomplete of key"** `o.id = (SELECT e.id ... WHERE e.record_key=o.record_key AND e.completed_at IS NULL ORDER BY e.created_at,e.id LIMIT 1)`. Idea: not an antijoin, so no hash anti-join; each candidate costs one IX_K index probe reading one entry (covering: `id` in the index); keeps the outer index-order early stop. Equivalent because the outer row is itself incomplete, so the subquery always returns a row. Risk: dependent subquery executed per candidate (same count as NOT EXISTS). Most promising. MariaDB: parses, same rows.
4. **Window `ROW_NUMBER() OVER (PARTITION BY record_key ORDER BY created_at, id)`** over all incomplete rows (`completed_at IS NULL`, not partition-filtered, so a key's rows in other partitions cannot be missed), outer `rn=1` + status/due/partition filters. Idea: one pass, no per-candidate probe. Cost: materialises all NEW+FAILED rows, no early stop; expected to lose when incomplete rows are many (S4/S5), possibly win for tiny `LIMIT`-less workloads. Path IX_S range `(status,completed_at)` is not usable for both statuses: scans incomplete rows via IX_S/IX_R full scan. MariaDB: parses, same rows.
5. **NOT EXISTS + `/*+ JOIN_ORDER(o, e) NO_BNL(e) */`.** Idea: forbid hash anti-join/BNL for `e` and fix order outer-then-probe, preserving `LIMIT` early exit. Risk: hint ignored if aliases mismatch; low. MariaDB: hint treated as comment/warning; same rows.
6. **NOT EXISTS + `/*+ INDEX(o IX_S) INDEX(e IX_K) */`.** Forces the intended access path. High risk: silently ignored/changes plan on schemas without these index names. Same rows. MariaDB: comment/warning.
7. **NOT EXISTS + `FORCE INDEX (IX_S)` / `FORCE INDEX (IX_K)`.** Same, SQL-standard MySQL syntax; errors if the index is missing. High risk. MariaDB: parses if indexes exist.

## keys_nonstrict (QI-keys_nonstrict-1..7)

Contract (branch): due NEW record with no earlier due NEW incomplete record of the same key (`status=:status`, `next_retry_at <= :now`), order `created_at, id`. Forms 1, 2, 3, 4, 6, 7 mirror strict (the subquery adds `status`/`next_retry_at`, so IX_K probes read the clustered row for those two columns: more expensive per candidate than strict; for the scalar form 3 the probe may scan several non-due heads of the key before the first due one, S5 hot keys).
5. **GROUP BY record_key ORDER BY MIN(created_at), MIN(id)** (main's shape + `completed_at IS NULL`). Idea: one pass over due NEW via IX_S / IX_R then a temp-table aggregate; no per-candidate probe. Risk: key order ties: ordering by `(MIN(created_at), MIN(id))` is not always the `(created_at,id)` of the key's first record (the min id can belong to a later record with the same second only when ids tie differently), so the boundary key at `LIMIT` may differ from the branch on ties; the harness equivalence may flag differing key sets at the limit only. No early stop. MariaDB: parses (ONLY_FULL_GROUP_BY ok), same except tie risk.
Window form 4 filters the partitions in the outer query (safe if one key maps to several partitions); pushing `partition_no IN` into the derived table is faster but assumes key -> partition is fixed (true with the real `PartitionHasher` and constant partition count).

## pages (QI-pages-1..7)

Contract: all NEW incomplete records of the given keys, `SELECT *`, `ORDER BY record_key, created_at, id`, one result per key set (a key list has no duplicates).
Ideal path: IX_K ref `(record_key=, completed_at IS NULL)` per key, rows in `(created_at, id)` order, clustered lookup for payload and the `status` check; sort on `record_key` only merges.

1. **IN list** (identical to the branch; control for the other forms). Range with up to 100 equality ranges; `eq_range_index_dive_limit` (200) is irrelevant for <=100 keys. MariaDB: same.
2. **IN + `/*+ INDEX(r IX_K) */`.** Pins IX_K against the optimizer choosing `status`-leading IX_S (it scans all NEW rows on S4). High risk (name). MariaDB: comment/warning.
3. **JSON_TABLE key table**: `JSON_TABLE(:recordKeysJson, '$[*]' COLUMNS (k VARCHAR(255) PATH '$')) jt JOIN outbox_record r ON r.record_key = jt.k` with `JOIN_ORDER(jt, r)`. Idea: fixed statement text (one prepared plan, no statement per list length), join drives IX_K lookups via `ref`, avoids the range optimizer's per-key dives. Risks: collation of `jt.k` must equal the column's (both default to the schema's default collation, `utf8mb4_0900_ai_ci`); if the server reports "Illegal mix of collations" add `COLLATE` equal to `outbox_record.record_key`'s; the IN list has the same semantics (collation equality). JSON escaping by the runner. MariaDB 10.6+: JSON_TABLE exists, hint ignored; collations may differ (utf8mb4_general_ci/uca1400) -> same rows if the collation of the column is kept; untested.
4. **`VALUES ROW(...)` derived table** `(VALUES ROW(?),ROW(?)) AS kt(k)` (MySQL 8.0.19+). Idea: same join as 3 without JSON. Risk: literal vs column collation coercion (literal is coercible, so safe); statement text changes with list length. MariaDB: **does not parse** (no `ROW(...)` table value constructor, no derived column list in that form).
5. **LATERAL**: `JSON_TABLE ... JOIN LATERAL (SELECT r.* ... WHERE r.record_key = jt.k ...) p`, outer `ORDER BY`. Idea: per-key derived table re-evaluated per key (ref on IX_K), the planner cannot reorder it, possibly avoids sorting by merging, but materialises each derived result (LONGTEXT) in a temp table. Likely slower than 3; included for the M5 comparison. MariaDB: **does not parse** (no LATERAL before 12? treated as unsupported).
6. **Per-key `UNION ALL`**: one `(SELECT * ... WHERE r.record_key = :item ...)` branch per key plus outer `ORDER BY`. Idea: guarantees one ref lookup per key on IX_K, no range analysis. Cost: UNION temp table materialises wide rows, statement text varies with the key count (up to 100 branches, 1000 max). MariaDB: parses, same rows.
7. **Derived key table by `SELECT ? AS k UNION ALL SELECT ?`** joined to `outbox_record`. Portable counterpart of 4 (MySQL and MariaDB). Join strategy as 3.

## count_statuses (QI-count_statuses-1..3)

Must keep working when `:statuses` contains `COMPLETED`, so **no `completed_at IS NULL` is added**. Result rows: one per status with at least one record (the repository fills zeros).
1. **One COUNT per status via `UNION ALL`** (`EACH`). Idea: each branch is a single-range index-only count on IX_S/IX_P (`status = ?` ref) instead of GROUP BY over `status IN (...)` ranges; no temp table (UNION ALL temp table has 3 rows). Branches with count 0 return a row with `cnt=0` (differs from GROUP BY, which omits them; the repository result map is identical after zero fill; the equivalence check must tolerate it, otherwise wrap in `SELECT * FROM (...) t WHERE cnt > 0`). MariaDB: same.
2. **JSON_TABLE + correlated scalar COUNT**, zero rows filtered by `cnt > 0` (exact rows of the branch). Fixed text. Same cost as 1. MariaDB 10.6+: parses.
3. **`/*+ INDEX(outbox_record IX_P) */` GROUP BY.** The narrow 3-column index is smaller than IX_S (7 columns, `record_key` 255 chars), so an index-only scan reads far fewer pages; the optimizer may choose by cost anyway. High risk (name). MariaDB: comment.
The count of NEW/FAILED is proportional to the status range (S2 about 13k rows) whichever form; `COMPLETED` (about 1M) is only counted in explicit COMPLETED requests.

## count_partitions (QI-count_partitions-1..4)

Parameter `:status` is generic, so `completed_at IS NULL` cannot be added; the split below keeps all rows.
1. **Split on `completed_at IS NULL` / `IS NOT NULL`, summed.** Idea: the `IS NULL` branch gets the full prefix `(status=, completed_at IS NULL, partition_no IN (...))` on IX_P/IX_S: up to 64-256 point ranges instead of scanning the entire NEW range with `partition_no` as a filter; the `IS NOT NULL` branch covers COMPLETED (empty for NEW, a range scan with in-index filter for COMPLETED). `CAST(COALESCE(SUM(c),0) AS UNSIGNED)` returns BIGINT (no DECIMAL). Result: one row. Needs `:partitions` bound twice. MariaDB: parses, same rows.
2. **`SKIP_SCAN(outbox_record IX_P)`** lets MySQL 8.0.13+ skip the unconstrained `completed_at` key part and range on `partition_no`; applies only if the optimizer accepts equality on `status` as prefix (hypothesis); otherwise ignored. MariaDB: no skip scan; comment.
3. **`INDEX(... IX_P)` hint** on the baseline: narrower covering index than the status-prefix IX_S. High risk (name).
4. **1 + hint on IX_P** in both branches. High risk.

## oldest_created (QI-oldest_created-1..4), mandatory comparison M2

`completed_at IS NULL` is already in the contract (status fixed NEW).
1. **`MIN(created_at)`** (branch). Hypothesis: no min/max shortcut because `partition_no` is an `IN` on a later key part; scans the `status='NEW' AND completed_at IS NULL` range in `created_at` order and filters, no stop, or the optimizer picks another index.
2. **`SELECT created_at ... ORDER BY created_at LIMIT 1`.** Reads IX_S in `created_at` order with in-index `partition_no` filtering (ICP) and **stops at the first owned-partition row**. Returns no row if none. With 64/256 owned partitions the scan is short; with 16 owned partitions of 256 it reads up to ~16x rows before the first hit (still bounded). MariaDB: same.
3. **Wrapped scalar** `SELECT (SELECT ... LIMIT 1) AS oldest`: form 2 with MIN-like one-row shape. Most promising. MariaDB: same.
4. **3 + `/*+ INDEX(outbox_record IX_S) */`** to make sure the ordered index is used rather than a partition-ish index with sort. High risk.

## oldest_due_nonstrict (QI-oldest_due_nonstrict-1..4), mandatory comparison M3

1. `MIN(o.next_retry_at)` (branch): range `next_retry_at <= :now` on IX_R, `partition_no` in-index; scans all due rows of the status (no stop).
2. `ORDER BY o.next_retry_at LIMIT 1` over IX_R: stops at the first due row of an owned partition.
3. Wrapped scalar of 2 (always one row).
4. 3 + `/*+ INDEX(o IX_R) */`. High risk. MariaDB: 1-3 same rows; 4 comment.

## oldest_due_strict (QI-oldest_due_strict-1..6), mandatory comparison M3

Predecessor condition: no earlier incomplete record of the same key (as `keys_strict`).
1. **`MIN(next_retry_at)` + NOT EXISTS (row constructor).** Main's shape: evaluates the probe for every due candidate, no early stop. Baseline for M3.
2. **`ORDER BY o.next_retry_at LIMIT :limit` + NOT EXISTS (row constructor)** (branch shape with the row comparison). Candidates in `next_retry_at` order from IX_R, probe each until the first unblocked one.
3. **ORDER BY LIMIT + scalar first-incomplete** (`o.id = (SELECT e.id ... ORDER BY created_at,id LIMIT 1)`); avoids antijoin conversion, one IX_K entry per probe. Promising.
4. **ORDER BY LIMIT + LEFT JOIN ... IS NULL** with the branch's `<=` range conjunct.
5. **Wrapped scalar** of form 2 (`SELECT (SELECT ... LIMIT 1)`): always one row, shape-equal to `MIN`.
6. **NOT EXISTS + `/*+ JOIN_ORDER(o, e) NO_BNL(e) INDEX(o IX_R) */`.** High risk (index name).
Risk: when blocked rows dominate, 2-6 walk many candidates before the first unblocked one (S5: 80 of 100 hot keys have a blocking head); `MIN` (1) has a constant cost per due row instead. M3 decides. MariaDB: all parse; hints are comments.

## Three most promising

1. `QI-keys_strict-3` / `QI-keys_nonstrict-3` (scalar first-incomplete): no hash antijoin, one covering index entry per probe, outer early stop kept.
2. `QI-oldest_created-3` and `QI-oldest_due_*` wrapped `ORDER BY ... LIMIT 1`: index order plus early stop, one row always.
3. `QI-count_partitions-1` (IS NULL / IS NOT NULL split): turns a full status-range scan into point ranges per partition.
