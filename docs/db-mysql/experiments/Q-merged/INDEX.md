# Merged query forms (MySQL 8.4)

Merged from Q-indep (QI-*) and Q-pgtransfer (QP-*). Every `QM-<query>-0` is the my-opt SQL at 270c42cf (reference). Index names IX_S, IX_K, IX_R, IX_P in the access-path columns are the 270c42cf indexes `idx_outbox_record_status_created_id`, `idx_outbox_record_key_completed_created_id`, `idx_outbox_record_status_retry_partition`, `idx_outbox_record_status_partition`; remarks "(status, NULL)" / "completed_at IS NULL" on those refer to the pre-270c42cf column lists. Forms were checked against the `OutboxRecordRepository` KDoc, usage map 2 and 4, and the scenarios; no form caps strict candidates before the anti-join.
Aliases are unified (`o`, `e`, `r`); `oldest_*` forms use a literal `LIMIT 1` (the repository binds 1; `:limit` is not referenced by these forms, the runner may still supply it).
Classes: `ok`, `hint` (names an index, needs a user decision), `hint-generic` (optimizer hint without index name), `code change` (needs runner or repository SQL building beyond plain `IN` expansion), `status-conditional` (valid only for NEW/FAILED), `MySQL-only syntax` (MariaDB does not parse). `/*+ ... */` hints are comments to MariaDB 11; `FORCE INDEX` errors when the index is missing.
Null/zero-row column: `MIN` returns exactly one row (NULL when nothing matches); a bare `ORDER BY ... LIMIT 1` returns no row; the wrapped scalar `SELECT (SELECT ... LIMIT 1)` returns exactly one row. The repository maps both to `null`.

Dropped as duplicates of the pre-270c42cf reference (now `QM-oldest_created-6` etc., see the rebase section; the new `-0` differs for the five rebased ids) (same SQL up to alias or literal `LIMIT 1`): `QI-pages-1` (pages-0), `QI-oldest_created-1`, `QP-oldest_created-1` (oldest_created-0), `QI-oldest_due_nonstrict-1`, `QP-oldest_due_nonstrict-1` (oldest_due_nonstrict-0), `QP-oldest_due_strict-2` (oldest_due_strict-0). Other duplicates are listed in the origin column.

## keys_strict

| QM id | Origin ids | Idea | Class | Runner parameter support | Access path (per proposers) | Null / zero-row |
|---|---|---|---|---|---|---|
| QM-keys_strict-0 | (reference) my-opt 270c42cf | 270c42cf SQL: no outer `completed_at IS NULL` (subquery keeps it) | ok | none (plain `IN` expansion where lists occur) | see usage map section 4 | n/a |
| QM-keys_strict-1 | QI-keys_strict-1, QP-keys_strict-1 | explicit LEFT JOIN ... e.id IS NULL anti-join with the expanded tie-break | ok | none (plain :partitions IN expansion) | outer IX_S (status, completed_at IS NULL) in (created_at, id) order with early stop; probe IX_K per candidate | LIMIT query, 0 rows = empty list |
| QM-keys_strict-2 | QI-keys_strict-2, QP-keys_strict-2 | NOT EXISTS with row-constructor tie-break (e.created_at, e.id) < (o.created_at, o.id), no <= conjunct | ok | none | same as form 1; row constructor may give a different range on IX_K | empty list |
| QM-keys_strict-3 | QI-keys_strict-3 | scalar 'first incomplete of key' o.id = (SELECT e.id ... ORDER BY e.created_at, e.id LIMIT 1); no antijoin, no hash anti-join | ok | none | outer IX_S early stop; one covering IX_K entry per probe | empty list |
| QM-keys_strict-4 | QI-keys_strict-4 | ROW_NUMBER() OVER (PARTITION BY record_key ORDER BY created_at, id) = 1 over all incomplete rows | ok | none | scans all NEW+FAILED (strict) / due NEW (non-strict) rows; no early stop | empty list |
| QM-keys_strict-5 | QI-keys_strict-5 | NOT EXISTS (expanded tie-break) + JOIN_ORDER(o, e) NO_BNL(e) | hint-generic | none | intended: outer IX_S, probe IX_K; hint names aliases, may be ignored with a warning because e is in the subquery block | empty list |
| QM-keys_strict-6 | QI-keys_strict-6, QP-keys_strict-4 | NOT EXISTS + INDEX(o IX_S) INDEX(e IX_K) optimizer hints | hint | none | forced outer IX_S, probe IX_K | empty list |
| QM-keys_strict-7 | QI-keys_strict-7 | NOT EXISTS + FORCE INDEX (IX_S) / FORCE INDEX (IX_K) (error if an index is missing) | hint | none | forced outer IX_S, probe IX_K | empty list |
| QM-keys_strict-8 | QP-keys_strict-3 | NOT EXISTS + NO_SEMIJOIN() in the subquery block (dependent subquery, no hash anti-join) | hint-generic | none | outer IX_S early stop; per-candidate probe IX_K | empty list |
| QM-keys_strict-9 | QP-keys_strict-5 | NOT EXISTS with tie-break spelled e.created_at <= o.created_at AND (e.created_at < o.created_at OR e.id < o.id) | ok | none | same as the reference; different OR shape for range analysis on IX_K | empty list |
| QM-keys_strict-10 | QP-keys_strict-6 | LEFT JOIN anti-join + JOIN_ORDER(o, e) | hint-generic | none | outer first (IX_S early stop), probe IX_K | empty list |
| QM-keys_strict-11 | pre-270c42cf my-opt reference (2a2663de) | former reference SQL (2a2663de): outer `completed_at IS NULL` re-added (E-candidate; `M2`/`M3` MIN baseline where MIN) | ok | none (plain `IN` expansion where lists occur) | see usage map section 4 | n/a |

## keys_nonstrict

| QM id | Origin ids | Idea | Class | Runner parameter support | Access path (per proposers) | Null / zero-row |
|---|---|---|---|---|---|---|
| QM-keys_nonstrict-0 | (reference) my-opt 270c42cf | 270c42cf SQL: no outer `completed_at IS NULL` (subquery keeps it) | ok | none (plain `IN` expansion where lists occur) | see usage map section 4 | n/a |
| QM-keys_nonstrict-1 | QI-keys_nonstrict-1, QP-keys_nonstrict-1 | explicit LEFT JOIN ... e.id IS NULL anti-join with the expanded tie-break | ok | none (plain :partitions IN expansion) | outer IX_S (status, completed_at IS NULL) in (created_at, id) order with early stop; probe IX_K per candidate | LIMIT query, 0 rows = empty list |
| QM-keys_nonstrict-2 | QI-keys_nonstrict-2, QP-keys_nonstrict-2 | NOT EXISTS with row-constructor tie-break (e.created_at, e.id) < (o.created_at, o.id), no <= conjunct | ok | none | same as form 1; row constructor may give a different range on IX_K | empty list |
| QM-keys_nonstrict-3 | QI-keys_nonstrict-3 | scalar 'first incomplete of key' o.id = (SELECT e.id ... ORDER BY e.created_at, e.id LIMIT 1); no antijoin, no hash anti-join | ok | none | outer IX_S early stop; one covering IX_K entry per probe (probe also reads status/next_retry_at from the clustered row; may skip several non-due heads) | empty list |
| QM-keys_nonstrict-4 | QI-keys_nonstrict-4 | ROW_NUMBER() OVER (PARTITION BY record_key ORDER BY created_at, id) = 1 over all incomplete rows that are NEW and due; partition filter outside the derived table | ok | none | scans all NEW+FAILED (strict) / due NEW (non-strict) rows; no early stop | empty list |
| QM-keys_nonstrict-5 | QI-keys_nonstrict-5 | GROUP BY record_key ORDER BY MIN(created_at), MIN(id) over due NEW rows (no per-candidate probe) | ok | none | due NEW rows of owned partitions via IX_S/IX_R, temp-table aggregate; no early stop. Key order differs from the (created_at, id) of the key's first due record when ids tie-break differently (equivalence at the LIMIT boundary may differ) | empty list |
| QM-keys_nonstrict-6 | QI-keys_nonstrict-6 | NOT EXISTS + JOIN_ORDER(o, e) NO_BNL(e) | hint-generic | none | outer IX_S, probe IX_K | empty list |
| QM-keys_nonstrict-7 | QI-keys_nonstrict-7, QP-keys_nonstrict-4 | NOT EXISTS + INDEX(o IX_S) INDEX(e IX_K) optimizer hints | hint | none | forced outer IX_S, probe IX_K | empty list |
| QM-keys_nonstrict-8 | QP-keys_nonstrict-3 | NOT EXISTS + NO_SEMIJOIN() in the subquery block | hint-generic | none | outer IX_S early stop; per-candidate probe IX_K | empty list |
| QM-keys_nonstrict-9 | QP-keys_nonstrict-5 | NOT EXISTS with tie-break spelled e.created_at <= o.created_at AND (e.created_at < o.created_at OR e.id < o.id) | ok | none | as reference, different OR shape | empty list |
| QM-keys_nonstrict-10 | QP-keys_nonstrict-6 | LEFT JOIN anti-join + JOIN_ORDER(o, e) | hint-generic | none | outer first, probe IX_K | empty list |
| QM-keys_nonstrict-11 | QP-keys_nonstrict-7 | GROUP BY record_key ORDER BY MIN(created_at), record_key (tie-break by key) | ok | none | as nonstrict-5; ties between keys with equal MIN(created_at) ordered by record_key | empty list |
| QM-keys_nonstrict-12 | QP-keys_nonstrict-8 | ROW_NUMBER() = 1 window with the partition filter inside the derived table (assumes record_key -> partition_no is a function, true with PartitionHasher) | ok | none | scans due NEW rows of owned partitions; no early stop | empty list |
| QM-keys_nonstrict-13 | pre-270c42cf my-opt reference (2a2663de) | former reference SQL (2a2663de): outer `completed_at IS NULL` re-added (E-candidate; `M2`/`M3` MIN baseline where MIN) | ok | none (plain `IN` expansion where lists occur) | see usage map section 4 | n/a |

## pages

| QM id | Origin ids | Idea | Class | Runner parameter support | Access path (per proposers) | Null / zero-row |
|---|---|---|---|---|---|---|
| QM-pages-0 | (reference) my-opt, QI-pages-1 | current my-opt SQL, unchanged | ok | none (plain `IN` expansion where lists occur) | see usage map section 4 | n/a |
| QM-pages-1 | QI-pages-2, QP-pages-5 | IN list + INDEX(r IX_K) optimizer hint | hint | none (IN expansion) | forced IX_K ref ranges per key | n/a (list of rows) |
| QM-pages-2 | QI-pages-3 | JSON_TABLE key table JOIN outbox_record, JOIN_ORDER(jt, r); fixed statement text | hint-generic; MySQL-only syntax (JSON_TABLE, MariaDB 10.6+ parses, hint comment) | `:recordKeysJson`: one string param, JSON array of the keys | jt drives IX_K ref lookups; sort on record_key only merges | n/a (list of rows); duplicates rows if the list holds collation-equal keys |
| QM-pages-3 | QI-pages-4 | VALUES ROW(...) key table JOIN outbox_record, JOIN_ORDER(kt, r) | hint-generic; MySQL-only syntax (VALUES ROW, derived column list) | `:recordKeysRows` -> `ROW(?),ROW(?),...` | kt drives IX_K ref lookups | n/a (list of rows); duplicates rows if the list holds collation-equal keys |
| QM-pages-4 | QI-pages-5 | JSON_TABLE JOIN LATERAL derived table per key, outer ORDER BY p.* | MySQL-only syntax (LATERAL, JSON_TABLE) | `:recordKeysJson` | per-key IX_K ref in a derived table, materialised per key | n/a |
| QM-pages-5 | QI-pages-6 | one SELECT per key joined by UNION ALL, top-level ORDER BY | code change | `/*EACH:recordKeys: UNION ALL */ ... /*END*/` with `:item` and `:status` per copy | one IX_K ref lookup per key; UNION temp table | n/a |
| QM-pages-6 | QI-pages-7 | derived key table of SELECT ? AS k UNION ALL ... JOIN outbox_record, JOIN_ORDER(kt, r) | hint-generic; code change (MySQL and MariaDB syntax) | `:recordKeysSelects` -> `SELECT ? AS k UNION ALL SELECT ? UNION ALL ...` | kt drives IX_K ref lookups | n/a; duplicates rows if the list holds collation-equal keys |
| QM-pages-7 | QP-pages-1 | DISTINCT key table from the IN list, CROSS JOIN LATERAL one IX_K probe per key | MySQL-only syntax (LATERAL) | none (IN expansion) | extra index-only DISTINCT pass over IX_K entries of the keys, then per-key ref | n/a |
| QM-pages-8 | QP-pages-2 | VALUES ROW key table + CROSS JOIN LATERAL | MySQL-only syntax (VALUES ROW, LATERAL) | `:recordKeysRows` -> `ROW(?),ROW(?),...` | per-key IX_K ref lookup, order pinned | n/a |
| QM-pages-9 | QP-pages-3 | JSON_TABLE key table with explicit utf8mb4_0900_ai_ci collation + CROSS JOIN LATERAL | MySQL-only syntax (JSON_TABLE, LATERAL) | `:recordKeysJson` | per-key IX_K ref lookup | n/a |
| QM-pages-10 | QP-pages-4 | per-key UNION ALL wrapped in a derived table, ordered outside | code change | `/*EACH:recordKeys: UNION ALL */ ... /*END*/` with `:item` and `:status` per copy | one IX_K ref lookup per key; derived table materialised, then sorted | n/a |
| QM-pages-11 | QP-pages-6 | DISTINCT key table (from IN list) plain JOIN, optimizer picks join order | ok | none (IN expansion) | key table materialised, then IX_K ref lookups | n/a |

## count_statuses

| QM id | Origin ids | Idea | Class | Runner parameter support | Access path (per proposers) | Null / zero-row |
|---|---|---|---|---|---|---|
| QM-count_statuses-0 | (reference) my-opt | current my-opt SQL, unchanged | ok | none (plain `IN` expansion where lists occur) | see usage map section 4 | n/a |
| QM-count_statuses-1 | QI-count_statuses-1, QP-count_statuses-4 | one COUNT(*) per status joined by UNION ALL (no completed_at predicate) | code change | `/*EACH:statuses: UNION ALL */ ... /*END*/` with `:item` twice per copy | single-range index-only count per status on IX_P/IX_S | one row per requested status incl. count 0 (GROUP BY omits empty statuses; repository zero-fills) |
| QM-count_statuses-2 | QI-count_statuses-2 | JSON_TABLE status list with correlated scalar COUNT, rows with cnt = 0 filtered out | MySQL-only syntax (JSON_TABLE; MariaDB 10.6+ parses) | `:statusesJson`: one string param, JSON array of status names | per-status ref count on IX_P/IX_S | omits zero rows like GROUP BY |
| QM-count_statuses-3 | QI-count_statuses-3 | GROUP BY + INDEX(IX_P) (narrow covering index) | hint | none | forced IX_P index-only range scan | omits zero rows |
| QM-count_statuses-4 | QP-count_statuses-1 | GROUP BY with completed_at IS NULL | status-conditional (statuses within NEW/FAILED only) | none | range (status, completed_at IS NULL) on IX_P/IX_S/IX_R | omits zero rows (COMPLETED would be silently missing) |
| QM-count_statuses-5 | QP-count_statuses-2 | per-status UNION ALL branches with completed_at IS NULL | status-conditional; code change | `/*EACH:statuses: UNION ALL */ ... /*END*/` with `:item` twice per copy | one ref range per status | one row per requested status incl. 0 |
| QM-count_statuses-6 | QP-count_statuses-3 | GROUP BY + completed_at IS NULL + INDEX(IX_P) | hint; status-conditional | none | forced IX_P | omits zero rows |

## count_partitions

| QM id | Origin ids | Idea | Class | Runner parameter support | Access path (per proposers) | Null / zero-row |
|---|---|---|---|---|---|---|
| QM-count_partitions-0 | (reference) my-opt | current my-opt SQL, unchanged | ok | none (plain `IN` expansion where lists occur) | see usage map section 4 | n/a |
| QM-count_partitions-1 | QI-count_partitions-1 | split into completed_at IS NULL / IS NOT NULL, summed (valid for every status) | code change | `:partitions` occurring twice (list bound once per occurrence) | IS NULL branch: point ranges per partition on IX_P/IX_S; IS NOT NULL branch: range with in-index filter | always exactly one row (SUM coalesced to 0) |
| QM-count_partitions-2 | QI-count_partitions-2 | SKIP_SCAN(IX_P) on the reference | hint | none | skip scan over completed_at, range on partition_no (if accepted) | one row |
| QM-count_partitions-3 | QI-count_partitions-3 | INDEX(IX_P) on the reference | hint | none | forced IX_P index-only scan of the status range | one row |
| QM-count_partitions-4 | QI-count_partitions-4 | form 1 with INDEX(IX_P) in both branches | hint; code change | `:partitions` occurring twice | as form 1 on IX_P | one row |
| QM-count_partitions-5 | QP-count_partitions-1 | completed_at IS NULL added | status-conditional (NEW/FAILED only) | none | range (status, NULL) then partition_no filter in index (IX_P/IX_S/IX_R) | one row |
| QM-count_partitions-6 | QP-count_partitions-2 | completed_at IS NULL + INDEX(IX_P) | hint; status-conditional | none | forced IX_P | one row |
| QM-count_partitions-7 | QP-count_partitions-3 | VALUES ROW(partition) JOIN outbox_record, completed_at IS NULL | status-conditional; MySQL-only syntax (VALUES ROW) | `:partitionsRows` -> `ROW(?),ROW(?),...` | one prefix probe (status, NULL, partition_no = p) per partition | one row |
| QM-count_partitions-8 | QP-count_partitions-4 | SKIP_SCAN without index name on the reference | hint-generic | none | skip scan if accepted | one row |

## oldest_created

| QM id | Origin ids | Idea | Class | Runner parameter support | Access path (per proposers) | Null / zero-row |
|---|---|---|---|---|---|---|
| QM-oldest_created-0 | (reference) my-opt 270c42cf | 270c42cf SQL: `SELECT created_at ... ORDER BY created_at LIMIT :limit`, no `completed_at`, maxrows one | ok | none (plain `IN` expansion where lists occur) | see usage map section 4 | ORDER BY LIMIT: no row when nothing matches |
| QM-oldest_created-1 | QI-oldest_created-2, QP-oldest_created-2 | ORDER BY created_at LIMIT 1 (stop at first owned-partition row) | ok | none | IX_S (status, NULL) in created_at order, partition_no in-index, early stop | bare: no row when nothing matches (reference: one NULL row) |
| QM-oldest_created-2 | QI-oldest_created-3 | scalar subquery wrapper of form 1: always one row | ok | none | as form 1 | always one row (NULL when nothing matches) |
| QM-oldest_created-3 | QI-oldest_created-4 | form 2 + INDEX(IX_S) | hint | none | forced IX_S | always one row |
| QM-oldest_created-4 | QP-oldest_created-3 | MIN(created_at) + INDEX(IX_S) | hint | none | forced IX_S | one NULL row |
| QM-oldest_created-5 | QP-oldest_created-4 | form 1 + INDEX(IX_S) | hint | none | forced IX_S, early stop | no row when nothing matches |
| QM-oldest_created-6 | pre-270c42cf my-opt reference (2a2663de), QI-oldest_created-1, QP-oldest_created-1 | former reference SQL (2a2663de): with `completed_at IS NULL`, MIN form (E-candidate; `M2`/`M3` MIN baseline where MIN) | ok | none (plain `IN` expansion where lists occur) | see usage map section 4 | MIN: one NULL row |

## oldest_due_strict

| QM id | Origin ids | Idea | Class | Runner parameter support | Access path (per proposers) | Null / zero-row |
|---|---|---|---|---|---|---|
| QM-oldest_due_strict-0 | (reference) my-opt 270c42cf | 270c42cf SQL: no outer `completed_at IS NULL` (subquery keeps it) | ok | none (plain `IN` expansion where lists occur) | see usage map section 4 | ORDER BY LIMIT: no row |
| QM-oldest_due_strict-1 | QI-oldest_due_strict-1 | MIN(next_retry_at) + NOT EXISTS row constructor (probe for every due candidate) | ok | none | outer IX_R range over all due rows; probe IX_K each; no early stop | always one row (NULL when empty) |
| QM-oldest_due_strict-2 | QP-oldest_due_strict-1 | MIN(next_retry_at) + NOT EXISTS with expanded tie-break | ok | none | as form 1 | always one row |
| QM-oldest_due_strict-3 | QI-oldest_due_strict-2, QP-oldest_due_strict-6 | ORDER BY next_retry_at LIMIT 1 + NOT EXISTS row constructor | ok | none (:limit not referenced) | outer IX_R in next_retry_at order, probe IX_K until the first unblocked row | bare: no row when nothing matches (reference: no row as well) |
| QM-oldest_due_strict-4 | QI-oldest_due_strict-3 | ORDER BY LIMIT 1 + scalar first-incomplete o.id = (SELECT e.id ... LIMIT 1) | ok | none | outer IX_R, one covering IX_K entry per probe | bare: no row |
| QM-oldest_due_strict-5 | QI-oldest_due_strict-4, QP-oldest_due_strict-3 | ORDER BY LIMIT 1 + LEFT JOIN ... IS NULL anti-join | ok | none | outer IX_R, probe IX_K | bare: no row |
| QM-oldest_due_strict-6 | QI-oldest_due_strict-5 | scalar wrapper around form 3 (row constructor), always one row | ok | none | as form 3 | always one row |
| QM-oldest_due_strict-7 | QI-oldest_due_strict-6 | NOT EXISTS + JOIN_ORDER(o, e) NO_BNL(e) INDEX(o IX_R) | hint; hint-generic | none | forced outer IX_R, probe IX_K | bare: no row |
| QM-oldest_due_strict-8 | QP-oldest_due_strict-4 | MIN over LEFT JOIN anti-join (control) | ok | none | all due rows, no early stop | always one row |
| QM-oldest_due_strict-9 | QP-oldest_due_strict-5 | ORDER BY LIMIT 1 + NOT EXISTS with NO_SEMIJOIN() in the subquery | hint-generic | none | outer IX_R, dependent subquery per candidate | bare: no row |
| QM-oldest_due_strict-10 | pre-270c42cf my-opt reference (2a2663de), QP-oldest_due_strict-2 (literal LIMIT 1) | former reference SQL (2a2663de): outer `completed_at IS NULL` re-added (E-candidate; `M2`/`M3` MIN baseline where MIN) | ok | none (plain `IN` expansion where lists occur) | see usage map section 4 | ORDER BY LIMIT: no row |

## oldest_due_nonstrict

| QM id | Origin ids | Idea | Class | Runner parameter support | Access path (per proposers) | Null / zero-row |
|---|---|---|---|---|---|---|
| QM-oldest_due_nonstrict-0 | (reference) my-opt 270c42cf | 270c42cf SQL: `SELECT next_retry_at ... ORDER BY next_retry_at LIMIT :limit`, no `completed_at`, maxrows one | ok | none (plain `IN` expansion where lists occur) | see usage map section 4 | ORDER BY LIMIT: no row when nothing matches |
| QM-oldest_due_nonstrict-1 | QI-oldest_due_nonstrict-2, QP-oldest_due_nonstrict-2 | ORDER BY next_retry_at LIMIT 1 (literal 1) | ok | none (:limit not referenced) | IX_R (status, NULL, next_retry_at range) in order, partition_no in-index, early stop | bare: no row when nothing matches (reference: one NULL row) |
| QM-oldest_due_nonstrict-2 | QI-oldest_due_nonstrict-3 | scalar subquery wrapper of form 1 | ok | none | as form 1 | always one row |
| QM-oldest_due_nonstrict-3 | QI-oldest_due_nonstrict-4 | form 2 + INDEX(o IX_R) | hint | none | forced IX_R | always one row |
| QM-oldest_due_nonstrict-4 | QP-oldest_due_nonstrict-3 | MIN + INDEX(o IX_R) | hint | none | forced IX_R | one NULL row |
| QM-oldest_due_nonstrict-5 | QP-oldest_due_nonstrict-4 | form 1 + INDEX(o IX_R) | hint | none | forced IX_R, early stop | no row when nothing matches |
| QM-oldest_due_nonstrict-6 | pre-270c42cf my-opt reference (2a2663de), QI-oldest_due_nonstrict-1, QP-oldest_due_nonstrict-1 | former reference SQL (2a2663de): MIN form with `completed_at IS NULL` (E-candidate; `M2`/`M3` MIN baseline where MIN) | ok | none (plain `IN` expansion where lists occur) | see usage map section 4 | MIN: one NULL row |

## Rebased to 270c42cf (2026-11-06)

Re-deduplicated against the new my-opt catalog (changed ids: keys_strict, keys_nonstrict, oldest_created, oldest_due_strict, oldest_due_nonstrict).
- `-0` of the five ids is now the 270c42cf SQL. The previous reference (2a2663de) was kept as an extra form: `QM-keys_strict-11`, `QM-keys_nonstrict-13`, `QM-oldest_created-6`, `QM-oldest_due_strict-10`, `QM-oldest_due_nonstrict-6` (origin ids unchanged, so QI-/QP- duplicates of the old reference, e.g. `QI-oldest_created-1`, `QI-oldest_due_nonstrict-1`, `QP-oldest_due_strict-2`, now point to these).
- No remaining form is byte-identical to a new `-0` (normalised for aliases, whitespace, `LIMIT :limit` vs `LIMIT 1`). Forms that equal the new `-0` apart from the redundant outer `completed_at IS NULL` and/or hints: keys_strict-5, -6, -8; keys_nonstrict-6, -7, -8 (hint variants of the reference, outer `completed_at IS NULL` kept as in the forms' origin); oldest_created-1 and -5; oldest_due_nonstrict-1 and -5 (differ from the new `-0` only by `completed_at IS NULL`, `LIMIT 1` literal, aliases); oldest_due_strict-7 and -9 (hint variants). They stay as forms: they are the `completed_at IS NULL` counterparts needed for M1/M2/M3 and the E-candidates, and traceable to the proposers.
- Hints renamed (hint class forms in all ids, also count_*): `status_completed_created_id` -> `status_created_id`, `status_completed_retry` -> `status_retry_partition`, `status_completed_partition` -> `status_partition`. No hint lost its target (every dropped index has a successor); forms hinting on the old R/P now hint on the narrower successor without `completed_at`.
- `maxrows` headers: the new `-0` of `oldest_created` and `oldest_due_nonstrict` has `maxrows: one` (the branch calls `withMaxRows(1)`), forms 1-5 of `oldest_created` still say `none`; set per experiment (M9) when comparing.
- S1 smoke of the forms of these five ids must be rerun (`S1-smoke.md`).

## Rejected

| Origin id | Reason |
|---|---|
| none | All forms of both proposers were semantically equal to the contract (rows, `(created_at, id)` tie-break, `completed_at IS NULL` only on NEW/FAILED paths or marked status-conditional, parameters unchanged). Two caveats, forms kept: (1) `QM-keys_nonstrict-5/11` (GROUP BY forms) order keys by `MIN(created_at)` plus `MIN(id)` or `record_key`, which can differ from the `(created_at, id)` of the key's first due record at ties and so at the LIMIT boundary; the key set is equal otherwise. (2) JOIN-with-key-table pages forms (`pages-2,3,4,6,8,9`) return a record twice if the key list contains two strings that are equal under the column collation; `pages-0` returns it once. |

## Runner support needed

Existing: `:partitions`, `:statuses`, `:recordKeys` expand to `?,?,...` (one bind per element, in order); scalars `:status`, `:now`, `:limit`. A named scalar or collection may occur several times in one statement; a collection is expanded and bound separately at every occurrence (needed by `count_partitions-1` and `-4`).

| Token | Used by | Expansion |
|---|---|---|
| `:recordKeysJson`, `:statusesJson` | pages-2, pages-4, pages-9 (recordKeys); count_statuses-2 (statuses) | ONE bind parameter: a JSON array string of the elements, e.g. `["k1","k2"]` (JSON-escaped; statuses by enum name). The generic spelling is `:<collection>Json`. |
| `:recordKeysRows`, `:partitionsRows` | pages-3, pages-8 (recordKeys); count_partitions-7 (partitions) | `ROW(?),ROW(?),...`, one bind per element, in order. Generic `:<collection>Rows`. The caller wraps it as `(VALUES :xRows) AS t(k)`. |
| `:recordKeysSelects` | pages-6 | `SELECT ? AS k UNION ALL SELECT ? UNION ALL ...`, one bind per element (the alias `k` is on the first SELECT only). The SQL supplies the surrounding parentheses. |
| `/*EACH:<collection>: <sep> */ body /*END*/` | pages-5, pages-10 (`recordKeys`, sep ` UNION ALL `); count_statuses-1, count_statuses-5 (`statuses`, sep ` UNION ALL `) | Repeat `body` once per element, joined by `<sep>` (the text between the second colon and `*/`, with its spaces). In each copy `:item` becomes `?` bound to the element (every occurrence of `:item` in a copy is its own bind); other named scalars (`:status`) become `?` and are bound in every copy. Minimum 1 element (callers short-circuit empty lists). |

Unified spellings: the proposers' `:recordKeyBranches`, `:statusBranches`, `:statusBranchesAll` are replaced by `EACH` (`QP-pages-4` -> `pages-10`, `QP-count_statuses-2` -> `count_statuses-5`, `QP-count_statuses-4` -> `count_statuses-1`); `:partitionsRows` and `:recordKeysRows` share one rule.

No form uses `SET_VAR`. `oldest_*` forms ignore `:limit`; `maxrows` stays `one`/`none` as in the reference header.
