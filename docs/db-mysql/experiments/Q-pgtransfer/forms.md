# Q-pgtransfer: PostgreSQL-transfer query forms for MySQL 8.4.13

Proposer: PostgreSQL-transfer. Every PostgreSQL idea below is a hypothesis; none is a fact about InnoDB. Form files are
`<query>/QP-<query>-<n>.sql` in harness catalog format. No indexes are proposed; "access path" names indexes of the `my-opt` DDL
(A = `idx_outbox_record_status_completed_created_id (status, completed_at, created_at, id, partition_no, next_retry_at, record_key)`,
B = `idx_outbox_record_key_completed_created_id (record_key, completed_at, created_at, id)`,
C = `idx_outbox_record_status_completed_retry (status, completed_at, next_retry_at, partition_no)`,
D = `idx_outbox_record_status_completed_partition (status, completed_at, partition_no)`).

## Rationale ideas (R-numbers used below)

- R1 Anti-join with early stop: no `GROUP BY`/`MIN`, outer scan in index order `(created_at, id)`, `LIMIT` stops it (rationale 3.1).
- R2 SARGable tie-break: `earlier.created_at <=` first, so the predecessor probe is one range on B (3.1 point 3).
- R3 Redundant `o.completed_at IS NULL` on the outer row so the index with `completed_at` in the prefix is eligible (3.1 point 2).
- R4 One B-probe per key for `pages` via a derived key table + `LATERAL`, pinning the join order (3.3).
- R5 Narrow covering index for counts, `completed_at IS NULL` only for NEW/FAILED (3.5, 3.6).
- R6 `ORDER BY ... LIMIT 1` vs `MIN`: equivalent plans, compare planning/driver overhead (3.7, 3.9).
- R7 `ORDER BY next_retry_at LIMIT 1` makes the anti-join run only on candidates (3.8).
- R8 One grouped statement instead of several counts (3.5).
- R9 Do not rely on estimates: pin plans (the PG `OFFSET 0` fence / join order). MySQL has no plan-cache duality, so only the
  "pin the order" half could matter (3.3).

General MySQL notes: `completed_at` is part of the A/B/C/D prefix, so `completed_at IS NULL` is an equality-like range (`ref` on a
nullable column, `IS NULL`), keeping `created_at`/`next_retry_at` order inside it; this is the MySQL substitute for the PG partial
index. Forms with the INDEX hint name the my-opt indexes: HIGH RISK. As an optimizer hint (`/*+ INDEX(...) */`) an unknown index only
raises a warning and is ignored (so on `my-main`'s schema the form silently equals the unhinted form); `FORCE INDEX` was avoided
because it errors. `completed_at IS NULL` is used only where the status is NEW/FAILED (count forms 1-3 below hold only for
`:status`/`:statuses` within NEW/FAILED; the catalog's `count_*` entries have no such predicate, see notes).
Every `earlier.id < o.id` comparison uses the column collation (`0900_ai_ci`), same as the current SQL. All forms keep
`:limit`-based truncation in SQL where the current text has it; `oldest_*` forms use a literal `LIMIT 1` (the runner binds limit 1
for these today).

MariaDB 11/12 column: parse = the statement is accepted; rows = same result.

## keys_strict (QP-keys_strict-1 .. 6)

| Form | Idea | Why it may help / not |
|---|---|---|
| 1 | R1 as explicit `LEFT JOIN ... WHERE earlier.id IS NULL` anti-join (id is PK, never NULL) | MySQL 8.0.17+ turns `LEFT JOIN ... IS NULL` into an antijoin, same as `NOT EXISTS`; may change join order/buffering choice (hash anti-join vs nested loop). Likely identical plan; PG did not distinguish. |
| 2 | R2 with a row constructor `(earlier.created_at, earlier.id) < (o.created_at, o.id)` | MySQL range-optimizes row-constructor comparisons on a multi-part prefix `(record_key, completed_at, created_at, id)`: one range on B instead of an OR expression. Drops the redundant `<=` conjunct. Risk: row comparison with a col that is nullable (`completed_at`) is not involved; semantics identical. |
| 3 | R1/R9 `NO_SEMIJOIN()` in the subquery block: forces a dependent subquery executed per outer row after the early-stopping outer index scan | Prevents a hash anti-join that would materialise/scan every candidate before `LIMIT`. Hint semantics for NOT EXISTS (antijoin) on 8.4 must be confirmed in EXPLAIN; if ignored it equals the current SQL. |
| 4 | R3/R9 hints `INDEX(o A) INDEX(earlier B)` | HIGH RISK: ties the statement to index names; ignored with a warning if absent. Pins the outer scan to the `(status, completed_at, created_at, id)` order so the `ORDER BY ... LIMIT` can stop early instead of a partition-range plan. |
| 5 | R2 simplified tie-break: `created_at <= o.created_at AND (created_at < o.created_at OR id < o.id)` | Logically equal (given `<=`, `id <` only matters on equal `created_at`). Fewer terms, same range on B. |
| 6 | R4/R9 anti-join as `LEFT JOIN` with `JOIN_ORDER(o, earlier)` | Pins outer-first so the scan in `ORDER BY` order drives; without it the optimizer may start from `earlier`. Low risk (alias hints). |

Access path: outer A (prefix `status`, `completed_at IS NULL`, order `created_at, id`, `partition_no`/`next_retry_at` covered), inner B.
Semantic risk: none known; forms 1 and 6 return one row per `o` because `earlier` rows are excluded by `IS NULL` (no duplicates).
Carry-over doubt: the PG win depends on a partial index (COMPLETED rows absent); in InnoDB, A contains all rows, but the
`completed_at IS NULL` prefix makes COMPLETED a separate contiguous range, so skipping it is free.
MariaDB: 1, 2, 5 parse and return the same rows; 3 `NO_SEMIJOIN()` and 4/6 MySQL-style hints are comments to MariaDB (ignored; MariaDB
has its own hint syntax), rows identical, plans may differ.

## keys_nonstrict (QP-keys_nonstrict-1 .. 8)

Forms 1-6 are the keys_strict forms with the inner `earlier.status = :status AND earlier.next_retry_at <= :now` (the due-predecessor
check). Same ideas and notes. Additional:

| Form | Idea | Notes |
|---|---|---|
| 7 | Main-style `GROUP BY record_key ORDER BY MIN(created_at)` with `completed_at IS NULL`; tie-break by `record_key` | Control of R1 in reverse: PG says it forces reading all due rows (S4 687 ms vs 1 ms). May still win on small backlogs (PG E24: single `HashAggregate` cheaper). Rows: same key set; order differs only where two keys have equal `MIN(created_at)` (ties; the contract orders by `created_at, id`), so an order-sensitive equivalence check must treat ties as equal. |
| 8 | Window function `ROW_NUMBER() OVER (PARTITION BY record_key ORDER BY created_at, id) = 1` in a derived table | Materialises all due rows (window sorts). Included as a counterweight; expected slow at S4/S5. Same rows and order as the anti-join form (earliest due incomplete NEW record per key, ordered by its `created_at, id`). |

MariaDB: 7 returns the same keys; 8 needs window functions (10.2+), parses, same rows.

## pages (QP-pages-1 .. 6)

| Form | Idea | Runner needs |
|---|---|---|
| 1 | R4 without new parameter shape: derived key table `SELECT DISTINCT record_key ... WHERE record_key IN (:recordKeys)` then `CROSS JOIN LATERAL` one B probe per key | Normal `IN` expansion. The DISTINCT side walks B per key once (index-only, loose/range) and costs one extra pass over the key's entries (hot keys: 2 000 entries). MySQL 8.0.14+ LATERAL. |
| 2 | R4 exact analogue of `unnest`: `(VALUES ROW(?),...) AS t(k)` + `LATERAL` | `:recordKeysRows` expanded to `ROW(?),ROW(?),...` (one ROW per key). MySQL 8.0.19+. Literal strings take the connection collation; if it differs from the column's, the equality may lose index use (check with EXPLAIN). |
| 3 | R4 with `JSON_TABLE` over one JSON array parameter | Bind `:recordKeysJson` as a single JSON string (e.g. built with a JSON library, `["k1","k2"]`). The column uses an explicit `utf8mb4_0900_ai_ci` collation to match `record_key`; the harness image default must be checked. |
| 4 | R4 as per-key `UNION ALL` branches wrapped in an ordering SELECT | `:recordKeyBranches` expanded to N copies of `SELECT * FROM outbox_record WHERE record_key = ? AND status = :status AND completed_at IS NULL` joined by `UNION ALL`; `:status` bound per branch. Plan is pinned by construction (rationale R9), no LATERAL needed; larger statement text per distinct key count. |
| 5 | R9 `INDEX(outbox_record B)` hint on the `IN` form | HIGH RISK (index name). Guards against the optimizer using A (status prefix) with `record_key` as a filter. |
| 6 | R4 plain `JOIN` to the derived `DISTINCT` key table (no LATERAL) | Normal `IN` expansion; lets the optimizer choose join order (may materialise the key table, then ref lookups on B). Fallback where LATERAL is unavailable. |

Access path: B (`record_key` = k, `completed_at IS NULL`, order `created_at, id` inside each key, no sort per key). `status = :status`
is not in B, so it is checked on the clustered row (one lookup per returned row); `SELECT *` needs the clustered row anyway.
Whether the PG "plan flip under load" has an analogue is doubtful: MySQL has no generic plans; the likely MySQL failure mode is a
bad `IN`-range plan or a filesort of all rows (ORDER BY `record_key` first). Semantic risk: ordering across keys is not defined by
the KDoc; within each key `(created_at, id)` is kept. Form 4 order comes from the outer `ORDER BY` only.
MariaDB: forms 1, 2, 3, 4, 6 LATERAL/VALUES ROW/JSON_TABLE availability: LATERAL absent (forms 1, 2, 3 do not parse); `JSON_TABLE` exists
from MariaDB 10.6 but not combined with LATERAL; 4, 5 (hint ignored) and 6 parse with the same rows.

## count_statuses (QP-count_statuses-1 .. 4)

The catalog entry has no `completed_at` predicate (it also serves the `count_statuses_all` shape). Forms 1-3 add it: valid only if
`:statuses` is a subset of {NEW, FAILED}; with COMPLETED in the set they would silently return no COMPLETED group (PG trap), so a
dialect using them must branch on the requested set (as the PG implementation does). Form 4 is safe for any set.

| Form | Idea | Notes |
|---|---|---|
| 1 | R5/R8 PG final: `WHERE status IN (:statuses) AND completed_at IS NULL GROUP BY status` | Range on A/C/D prefix `(status, completed_at)`; D is covering and narrow. |
| 2 | R8 inverse: per-status `UNION ALL` branches with `IS NULL` | `:statusBranches` runner expansion (value bound twice per branch). Gives MySQL one tight `ref` range per status instead of a `range` over `IN`; mostly equal to form 1. |
| 3 | R5 `INDEX(outbox_record D)` hint | HIGH RISK. Forces the narrowest covering index (A is 5x wider). |
| 4 | Per-status `UNION ALL` branches without `completed_at` | `:statusBranchesAll`. Counts COMPLETED too (cost grows with history; same as `count_statuses_all`). Rows: one row per requested status (even with 0), unlike `GROUP BY` which omits empty statuses; the consumer maps missing to 0 in the `GROUP BY` shape, so check the repository code before use. |

MariaDB: 1, 2, 4 parse and return equivalent rows (hint in 3 ignored).

## count_partitions (QP-count_partitions-1 .. 4)

| Form | Idea | Notes |
|---|---|---|
| 1 | R5 PG final: add `completed_at IS NULL` (NEW/FAILED only) | A/C/D range `(status, completed_at, partition_no IN ...)`; MySQL scans the whole `(NEW, NULL)` range and tests `partition_no` in the index (D is index-only). No `partition_no`-leading index, so cost grows with all NEW rows, not just owned partitions; PG's D3 had the same shape. |
| 2 | R5 plus `INDEX(outbox_record D)` | HIGH RISK. Pins D instead of A/C. |
| 3 | `VALUES ROW(partition)` joined to the table | `:partitionsRows` expansion to `ROW(?),...`. Probes `(status, completed_at, partition_no = p)` as 64 (or 256) eq-refs/prefix ranges, which the `IN` range already does; offered because the join order can be pinned. MariaDB: VALUES ROW not available. |
| 4 | R5 skip-scan: no `completed_at` predicate (safe for COMPLETED) with `SKIP_SCAN` hint | Speculative: skip scan skips distinct `completed_at` values in D, then ranges `partition_no`. COMPLETED has one value per row, so it is only sensible if the optimizer's cost model accepts it; likely rejected or slow for COMPLETED. |

MariaDB: 1 and 2 parse and return same rows; 3 does not parse; 4 parses (hint ignored).

## oldest_created (QP-oldest_created-1 .. 4)

| Form | Idea | Notes |
|---|---|---|
| 1 | R6 `MIN(created_at)` with `completed_at IS NULL` | This is the current catalog text. MySQL cannot loose-scan `MIN` across `partition_no IN`, so it reads A's `(NEW, NULL)` range in `created_at` order until the first owned partition (rows tested on index columns). |
| 2 | R6 `ORDER BY created_at LIMIT 1` | Same access; the early stop is the explicit `LIMIT`. With `completed_at IS NULL` required (NEW only). Returns zero rows instead of a NULL row when nothing matches: the repository must map no row to null (maxrows `none`; fetch via `query.optional()` style). |
| 3 | R6/R9 form 1 plus `INDEX(outbox_record A)` | HIGH RISK. Stops the optimizer choosing a partition-range/ C/D plan. |
| 4 | Form 2 plus the same hint | HIGH RISK. |

Why it may not carry over: PG's difference came from plan caching, not execution. MySQL has none, so forms 1/2 may be equal;
measuring decides. Not proposed: `ORDER BY created_at, id`, no benefit.
MariaDB: all parse; hints ignored; rows equal (2 returns no row for no match).

## oldest_due_strict (QP-oldest_due_strict-1 .. 6)

All use the my-opt predicates (`completed_at IS NULL`, `created_at <=` first).

| Form | Idea | Notes |
|---|---|---|
| 1 | `MIN(next_retry_at)` + `NOT EXISTS` (main shape plus R3) | PG: every due row passes the anti-join first (532 ms vs 0.75 ms). |
| 2 | R7 `ORDER BY next_retry_at LIMIT 1` + `NOT EXISTS` (current) | Outer C order, stop at first unblocked row; needs the outer scan not to filesort. |
| 3 | R7 as `LEFT JOIN ... IS NULL`, `ORDER BY ... LIMIT 1` | Same ideas as keys_strict 1. |
| 4 | `MIN` + `LEFT JOIN` anti-join | MIN over a join: unlikely early stop; control. |
| 5 | R7 + `NO_SEMIJOIN()` in the subquery | Per-candidate dependent subquery stops after the first accepted row. |
| 6 | R7 + row-constructor tie-break | R2 variant. |

Access path: outer C (`status`, `completed_at IS NULL`, `next_retry_at <= :now`, order `next_retry_at`, `partition_no` covered), inner B.
Risk: the outer uses `next_retry_at` order, but the optimizer may pick A (order `created_at`) and filesort. Both `MIN` and
`ORDER BY` forms return NULL / no row when empty (see oldest_created form 2). MariaDB: parse and same rows for all (hint ignored).

## oldest_due_nonstrict (QP-oldest_due_nonstrict-1 .. 4)

| Form | Idea | Notes |
|---|---|---|
| 1 | R6 `MIN` with `completed_at IS NULL` (current) | C gives `next_retry_at` order; with `partition_no IN` as a non-prefix filter, `MIN` is not "optimized away" but stops at the first owned row. |
| 2 | R6 `ORDER BY next_retry_at LIMIT 1` | Same plan expected; PG kept `MIN` here for plan-cache reasons that do not exist in MySQL. |
| 3 | form 1 + `INDEX(o C)` | HIGH RISK. |
| 4 | form 2 + `INDEX(o C)` | HIGH RISK. |

MariaDB: parse and same rows.

## Runner support summary

- Normal `IN (...)` expansion only: keys_strict 1-6, keys_nonstrict 1-8, pages 1, 5, 6, count_statuses 1, 3, count_partitions 1, 2, 4, all oldest_*.
- New expansions needed (markers appear as `-- runner:` comment lines in the file, the harness does not need to be edited):
  - `:recordKeysRows` -> `ROW(?),ROW(?),...` (pages-2); `:partitionsRows` -> `ROW(?),...` (count_partitions-3).
  - `:recordKeysJson` -> one JSON array string bound once (pages-3).
  - `:recordKeyBranches`, `:statusBranches`, `:statusBranchesAll` -> N textual branches joined by `UNION ALL` with per-branch parameter binding (pages-4, count_statuses-2/4).
- Semantic equivalence checks: `oldest_*` ORDER BY forms return no row where `MIN` returns one NULL row; key forms 7 (nonstrict) tie order.
