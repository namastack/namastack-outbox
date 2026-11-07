# Q-merged INDEX

Merged and deduplicated SQL forms from Q-indep (independent proposer) and Q-pgx (PostgreSQL-transfer proposer). Base = branch SQL (`mdb-opt`), main = `mdb-main`. Each file `<query>/<id>.sql` keeps the base catalog header (`id/group/kind/maxrows/source`) plus `origin` and `class`; copy the chosen form to `queries/<query id>.sql` for an experiment. Index names in hints/access paths refer to the branch DDL: idx1 `idx_outbox_record_status_completed_created_id (status, completed_at, created_at, id, partition_no, next_retry_at, record_key)`, idx2 `idx_outbox_record_key_completed_created_id (record_key, completed_at, created_at, id)`, idx3 `..._status_completed_retry (status, completed_at, next_retry_at, partition_no)`, idx4 `..._status_completed_partition (status, completed_at, partition_no)`.

Classes: ok, hint, mariadb-only, code, status-conditional, invalid. Forms marked "also a hint" under status-conditional need both decisions.

Partition determinism check: `OutboxRecord.Builder.build` sets `partition = PartitionHasher.getPartitionForRecordKey(key)` (256 fixed partitions), so partition_no is a pure function of record_key (not schema-enforced). Forms that push `partition_no IN` below a per-key window are therefore NOT rejected; they carry a note.

No form was classified invalid. No form caps the strict key candidates before the anti-join (K3).

Syntax check on MariaDB 12 (throw-away container, literals): Q-indep pages/values-cte, count_statuses/values-subquery, count_statuses/union-per-status all parse and return expected rows.


## keys_strict

| merged id | origin(s) | idea | class | access path needed | notes |
|---|---|---|---|---|---|
| ks-00-base | catalog mdb-opt; branch SQL | Branch SQL | ok | per branch DDL | Branch SQL. Order (created_at, id) of the candidate record. |
| ks-00-main | catalog mdb-main | Main branch SQL (header from main catalog) | ok | main DDL indexes | Reference only; shape/order may differ from branch (e.g. GROUP BY/MIN, no completed_at). |
| ks-01-split | Q-indep/keys_strict/ne-split, Q-pgx/keys_strict/ks_split | Two NOT EXISTS (created_at < ; created_at = AND id <) replace the OR; no <= conjunct | ok | idx_outbox_record_key_completed_created_id (record_key, completed_at, created_at, id) for both probes; outer idx_outbox_record_status_completed_created_id ordered scan | Same rows/order as base. Two probes per candidate. |
| ks-02-tuple | Q-indep/keys_strict/ne-tuple, Q-pgx/keys_strict/ks_tuple | Row-constructor (created_at,id) < (created_at,id) predecessor test | ok | key index (record_key, completed_at, created_at, id); outer status index | MariaDB likely does not range-optimize the row comparison. Same rows/order. |
| ks-03-leftjoin | Q-indep/keys_strict/lj-antijoin, Q-pgx/keys_strict/ks_leftjoin | LEFT JOIN ... earlier.id IS NULL anti-join (not-exists optimization) | ok | outer status_completed_created_id (ordered, covering); inner key index range | Optimizer may reorder tables and lose the ORDER BY early stop. Same rows/order. |
| ks-04-leftjoin-straight | Q-indep/keys_strict/lj-straight | 03-leftjoin with STRAIGHT_JOIN pinning o as driving table | ok | as 03 | STRAIGHT_JOIN is a join-order hint without index names (portable MySQL/MariaDB); classed ok, user may want to treat it as a hint. |
| ks-05-head | Q-pgx/keys_strict/ks_head | o.id = (first incomplete id of the key ORDER BY created_at,id LIMIT 1) | ok | key index (record_key, completed_at, created_at, id), index-only | Dependent subquery per candidate; equivalent because o itself is incomplete. Same rows/order. |
| ks-06-window | Q-indep/keys_strict/win-head | ROW_NUMBER() per record_key over all incomplete rows; status/partition/due filtered outside the window | ok | full scan of incomplete rows (status_completed_created_id or key index) + window sort; no LIMIT early stop | Expected bad on large backlog (S4), control form. Same rows/order. |
| ks-07-window-part | Q-pgx/keys_strict/ks_window | 06-window with partition_no IN pushed into the derived table | ok | as 06 but derived table shrinks to owned partitions | Pushdown relies on partition_no being a pure function of record_key: verified (OutboxRecord.Builder.build sets partition = PartitionHasher.getPartitionForRecordKey(key); not enforced by schema). Same rows/order. |
| ks-08-force-outer | Q-indep/keys_strict/ne-split-force-idx1 | 01-split with FORCE INDEX (idx_outbox_record_status_completed_created_id) on outer | hint | status_completed_created_id for outer, key index for probes | Error 1176 if index missing (e.g. main DDL). |
| ks-09-force-both | Q-pgx/keys_strict/ks_force | Branch SQL with FORCE INDEX on outer (status_completed_created_id) and subquery (key_completed_created_id) | hint | as named | Error 1176 if either index missing. |

## keys_nonstrict

| merged id | origin(s) | idea | class | access path needed | notes |
|---|---|---|---|---|---|
| kn-00-base | catalog mdb-opt; branch SQL | Branch SQL | ok | per branch DDL | Branch SQL. Order (created_at, id) of the first due record per key. |
| kn-00-main | catalog mdb-main | Main branch SQL (header from main catalog) | ok | main DDL indexes | Reference only; shape/order may differ from branch (e.g. GROUP BY/MIN, no completed_at). |
| kn-01-split | Q-indep/keys_nonstrict/ne-split, Q-pgx/keys_nonstrict/kn_split | Two NOT EXISTS over earlier due NEW incomplete rows (< ; = AND id <) | ok | outer status_completed_created_id; inner key index then PK lookup for status/next_retry_at (not in key index) | Same rows/order as base. |
| kn-02-tuple | Q-indep/keys_nonstrict/ne-tuple | Row-constructor predecessor comparison | ok | as 01 | May not become a composite range on MariaDB. Same rows/order. |
| kn-03-leftjoin | Q-pgx/keys_nonstrict/kn_leftjoin | LEFT JOIN anti-join, status/due restriction in ON | ok | outer status index ordered; inner key index + row filter on status/next_retry_at | Conditions on earlier must stay in ON. Join reorder may force filesort. |
| kn-04-leftjoin-straight | Q-indep/keys_nonstrict/lj-antijoin | 03-leftjoin with STRAIGHT_JOIN (o driving) | ok | as 03 | Kept apart from 03 because STRAIGHT_JOIN changes the plan space. STRAIGHT_JOIN is a join-order hint without index names; classed ok. |
| kn-05-head | Q-pgx/keys_nonstrict/kn_head | o.id = first due incomplete NEW row of the key (ORDER BY created_at,id LIMIT 1) | ok | key index, then lookup for status/due filter | Hot keys (S5) with many non-due heads may scan long. Same rows/order. |
| kn-06-window | Q-indep/keys_nonstrict/win-head | ROW_NUMBER over due NEW incomplete rows of all partitions; partition filter outside | ok | status_completed_created_id range, covering; window sort | Materializes due rows of all partitions; no LIMIT early stop. Same rows/order. |
| kn-07-window-part | Q-pgx/keys_nonstrict/kn_window | 06-window with partition_no IN inside the derived table | ok | status index range, covering | Needs partition_no = pure function of record_key (verified in OutboxRecord.Builder.build; not schema-enforced). Same rows/order. |
| kn-08-groupby | Q-pgx/keys_nonstrict/kn_groupby | Main-shaped GROUP BY record_key ORDER BY MIN(created_at) + completed_at IS NULL | ok | status index covering; temp table + filesort | ORDER DIFFERS from branch (created_at,id of first eligible record): ties on equal MIN(created_at) are unordered, so the key subset at the LIMIT boundary may differ. Key set (without LIMIT) identical. Contract does not state a key order; user decision. |
| kn-09-groupby-minid | Q-indep/keys_nonstrict/group-min | GROUP BY record_key ORDER BY MIN(created_at), MIN(id) | ok | as 08 | ORDER DIFFERS from branch: MIN(id) over the key group need not be the id of the head record, so ties order differently. Key set identical. |
| kn-10-force-outer | Q-indep/keys_nonstrict/ne-split-force-idx1 | 01-split with FORCE INDEX on the outer table | hint | status_completed_created_id | Error 1176 if index missing. |
| kn-11-force-both | Q-pgx/keys_nonstrict/kn_force | Branch SQL with FORCE INDEX on outer and subquery | hint | status_completed_created_id + key_completed_created_id | Error 1176 if either index missing. |

## pages

| merged id | origin(s) | idea | class | access path needed | notes |
|---|---|---|---|---|---|
| pa-00-base | catalog mdb-opt; branch SQL | Branch SQL | ok | per branch DDL | Branch SQL. |
| pa-00-main | catalog mdb-main | Main branch SQL (header from main catalog) | ok | main DDL indexes | Reference only; shape/order may differ from branch (e.g. GROUP BY/MIN, no completed_at). |
| pa-01-latejoin | Q-indep/pages/late-join | Deferred join: narrow (id, record_key, created_at) from the key index, then full rows by PK with status checked on r | ok | key index covering for inner; PK eq_ref | Same rows/order. Optimizer may still join then filesort wide rows. |
| pa-02-semi-in | Q-indep/pages/semi-idx1 | r.id IN (SELECT id ... status AND completed_at IS NULL AND record_key IN) then ORDER BY on r | ok | status_completed_created_id prefix scan filtered by record_key (no seek by key) | Counter-example: cost proportional to incomplete backlog unless the semi-join becomes a key lookup. Same rows/order. |
| pa-03-force-key | Q-indep/pages/force-idx2, Q-pgx/pages/pg_force_key | FORCE INDEX (idx_outbox_record_key_completed_created_id) | hint | key index, one range per key, then PK lookup | Error 1176 if index missing. |
| pa-04-ignore-status | Q-pgx/pages/pg_ignore_status | IGNORE INDEX of the three status-leading indexes | hint | key index (by exclusion) | Error 1176 if any named index missing. |
| pa-05-setstmt-nomerge | Q-pgx/pages/pg_setstmt_nomerge | SET STATEMENT optimizer_switch=index_merge=off,rowid_filter=off FOR SELECT ... | mariadb-only | optimizer choice minus merge/rowid filter | MariaDB-only syntax, no index names; JDBC/prepared-statement acceptance unverified. |
| pa-06-json-table | Q-indep/pages/json-table | JSON_TABLE derived key table joined to the key index | code | key index | CODE: bind one JSON array string :recordKeysJson (e.g. ["k1","k2"]) instead of the expanded :recordKeys. Collation of JSON_TABLE column vs record_key. |
| pa-07-json-straight | Q-pgx/pages/pg_json_straight | 06-json-table with STRAIGHT_JOIN and ORDER BY j.k | code | key index, one probe per key | CODE: same binding as 06 (:recordKeysJson JSON string). ORDER BY j.k equals r.record_key via join equality. |
| pa-08-values-cte | Q-indep/pages/values-cte | Table value constructor as derived key CTE | code | key index | CODE: :recordKeysRows must expand to (?),(?),... one parenthesized row per key. Syntax verified on MariaDB 12 with literals. Not portable to MySQL (needs VALUES ROW()). |
| pa-09-union-per-key | Q-indep/pages/union-per-key | One equality probe per key combined with UNION ALL (shown for 2 keys) | code | key index | CODE: statement built per key count, parameters :recordKey1..:recordKeyN. Syntax verified with 2 keys on MariaDB 12. UNION materializes then sorts. |

## count_statuses

| merged id | origin(s) | idea | class | access path needed | notes |
|---|---|---|---|---|---|
| cs-00-base | catalog mdb-opt; branch SQL | Branch SQL | ok | per branch DDL | Branch SQL. No main equivalent (main issues count_new/count_failed/count_completed per status). |
| cs-01-use-idx4 | Q-indep/count_statuses/use-idx4 | USE INDEX (idx_outbox_record_status_completed_partition), narrowest covering index | hint | status_completed_partition | Error 1176 if index missing. Correct for any status set. |
| cs-02-split-null-sum | Q-indep/count_statuses/split-null | UNION ALL of per-status grouped counts for completed_at IS NULL / IS NOT NULL, summed outside | ok | status_completed_* prefix (status, completed_at) | Correct for any status set (IS NOT NULL only partitions rows). :statuses is bound twice: harness support for repeated collection params unverified (works with named-parameter expansion). SUM returns DECIMAL (getLong ok). |
| cs-03-split-null-count | Q-pgx/count_statuses/cs_split_null | UNION ALL of raw status rows (NULL / NOT NULL halves), COUNT(*) GROUP BY outside | ok | as 02 | Correct for any status set. :statuses bound twice (see 02). Materializes one row per matching record (huge for COMPLETED); kept apart from 02 for that reason. |
| cs-04-values-subquery | Q-indep/count_statuses/values-subquery | Status list as CTE, one correlated COUNT(*) per status | code | status prefix of any status_completed_* index | CODE: :statusesRows must expand to (?),(?),... rows. Syntax verified on MariaDB 12. Returns zero rows for absent statuses (repository zero-fills anyway). |
| cs-05-union-per-status | Q-indep/count_statuses/union-per-status | One scalar COUNT per status via UNION ALL (shown for 2) | code | status_completed_partition / _retry | CODE: statement built per status with :status1..:statusN (label column bound too). Syntax verified on MariaDB 12. Returns zero rows for absent statuses. |
| cs-06-incomplete | Q-pgx/count_statuses/cs_incomplete | Add completed_at IS NULL so the range skips COMPLETED entries | status-conditional | status_completed_partition covering | Returns 0 for COMPLETED: valid only when the code selects it for statuses subset of {NEW, FAILED}. |
| cs-07-incomplete-force | Q-pgx/count_statuses/cs_incomplete_force | 06 + FORCE INDEX status_completed_partition | status-conditional | status_completed_partition | Also a hint (error 1176 if index missing); status-conditional as 06. |
| cs-08-incomplete-ignore-wide | Q-pgx/count_statuses/cs_incomplete_ignore_wide | 06 + IGNORE INDEX of the 7-column index | status-conditional | status_completed_retry or _partition | Also a hint (error 1176 if index missing); status-conditional as 06. |

## count_partitions

| merged id | origin(s) | idea | class | access path needed | notes |
|---|---|---|---|---|---|
| cp-00-base | catalog mdb-opt; branch SQL | Branch SQL | ok | per branch DDL | Branch SQL. Main SQL is textually identical, so no 00-main. |
| cp-01-use-idx4 | Q-indep/count_partitions/use-idx4 | USE INDEX (idx_outbox_record_status_completed_partition) | hint | status_completed_partition covering | Error 1176 if index missing. Correct for any status. |
| cp-02-split-null | Q-indep/count_partitions/split-null, Q-pgx/count_partitions/cp_split_null | Sum of two COUNT(*) (completed_at IS NULL / IS NOT NULL) so partition_no is the next key part: multi-range seek per owned partition | ok | status_completed_partition covering (also _created_id / _retry prefix) | Merged: Q-indep wraps in a derived UNION ALL + CAST(SUM) AS SIGNED, Q-pgx uses two scalar subqueries; same idea, text of Q-pgx kept (BIGINT natively). Correct for any status. :partitions bound twice (harness repeat-binding unverified). NOT NULL half is a full range for COMPLETED. |
| cp-03-split-null-idx4 | Q-indep/count_partitions/split-null-idx4 | 02 (Q-indep derived/CAST text) with USE INDEX on both branches | hint | status_completed_partition | Error 1176 if index missing; :partitions bound twice. |
| cp-04-incomplete | Q-pgx/count_partitions/cp_incomplete | Add completed_at IS NULL so partition_no IN becomes a multi-range | status-conditional | status_completed_partition covering | Returns 0 for COMPLETED; countRecordsByPartitions is public and accepts any status (only caller passes NEW): valid only if code selects it for non-COMPLETED status. |
| cp-05-incomplete-force | Q-pgx/count_partitions/cp_incomplete_force | 04 + FORCE INDEX status_completed_partition | status-conditional | status_completed_partition | Also a hint (error 1176 if index missing); status-conditional as 04. |
| cp-06-group-sum | Q-pgx/count_partitions/cp_group_sum | Per-partition GROUP BY then SUM, completed_at IS NULL | status-conditional | status_completed_partition in index order | Status-conditional as 04; measurement of grouping strategy; CAST to SIGNED. |

## oldest_created

| merged id | origin(s) | idea | class | access path needed | notes |
|---|---|---|---|---|---|
| oc-00-base | catalog mdb-opt 2a2663de | Branch SQL: MIN(created_at) with completed_at IS NULL, no setMaxRows | ok | per branch DDL | Branch updated 2026-11-05 (a06a74c0 -> 2a2663de); one row, NULL when none. |
| oc-06-order-limit | catalog mdb-opt a06a74c0; = Q-indep/oldest_created/ord-limit, Q-pgx/oldest_created/oc_order | ORDER BY created_at LIMIT :limit (previous branch SQL) | ok | status_completed_created_id | Zero rows when none. M2 comparison form. |
| oc-00-main | catalog mdb-main | Main branch SQL (header from main catalog) | ok | main DDL indexes | Reference only; shape/order may differ from branch (e.g. GROUP BY/MIN, no completed_at). |
| oc-01-min (= oc-00-base + setMaxRows 1) | Q-indep/oldest_created/min-agg, Q-pgx/oldest_created/oc_min | MIN(created_at) with completed_at IS NULL | ok | status_completed_created_id, index-only; likely no early stop | One row, NULL when none (main shape) instead of zero rows; repository must treat NULL as no value. |
| oc-02-min-of-limit | Q-indep/oldest_created/min-over-limited, Q-pgx/oldest_created/oc_min_of_limit | MIN over derived ORDER BY created_at LIMIT :limit | ok | status_completed_created_id | One row, NULL when none. Equal for :limit >= 1. |
| oc-03-order-id | Q-indep/oldest_created/ord-limit-id | Base with ORDER BY created_at, id | ok | status_completed_created_id | Same values returned (ties give equal created_at). |
| oc-04-order-force | Q-indep/oldest_created/ord-limit-force-idx1, Q-pgx/oldest_created/oc_order_force | Base + FORCE INDEX (idx_outbox_record_status_completed_created_id) | hint | status_completed_created_id | Error 1176 if index missing. |
| oc-05-min-force | Q-pgx/oldest_created/oc_min_force | 01-min + FORCE INDEX status_completed_created_id | hint | status_completed_created_id | Error 1176 if index missing. |

## oldest_due_strict

| merged id | origin(s) | idea | class | access path needed | notes |
|---|---|---|---|---|---|
| ods-00-base | catalog mdb-opt; = Q-indep/oldest_due_strict/ne-ord, Q-pgx/oldest_due_strict/ods_order | Branch SQL | ok | per branch DDL | Branch SQL (also = Q-indep ne-ord, Q-pgx ods_order). Zero rows when none. |
| ods-00-main | catalog mdb-main | Main branch SQL (header from main catalog) | ok | main DDL indexes | Reference only; shape/order may differ from branch (e.g. GROUP BY/MIN, no completed_at). |
| ods-01-min | Q-indep/oldest_due_strict/ne-min, Q-pgx/oldest_due_strict/ods_min | MIN(next_retry_at) with branch NOT EXISTS | ok | outer status_completed_retry; inner key index | Probe for every due row, no early stop. One row, NULL when none. |
| ods-02-split | Q-indep/oldest_due_strict/split-ord, Q-pgx/oldest_due_strict/ods_split | Two NOT EXISTS (< ; = AND id <), ORDER BY next_retry_at LIMIT | ok | outer status_completed_retry; inner key index | Two probes per candidate. |
| ods-03-split-min | Q-indep/oldest_due_strict/split-min | MIN form with split predecessor test | ok | as 02 | No early stop. One row, NULL when none. |
| ods-04-leftjoin | Q-pgx/oldest_due_strict/ods_leftjoin | LEFT JOIN anti-join, ORDER BY next_retry_at LIMIT | ok | outer status_completed_retry; inner key index | Join reorder breaks the order. |
| ods-05-leftjoin-straight | Q-indep/oldest_due_strict/lj-ord | 04 with STRAIGHT_JOIN | ok | as 04 | STRAIGHT_JOIN is a join-order hint without index names; classed ok. |
| ods-06-head | Q-pgx/oldest_due_strict/ods_head | o.id = first incomplete id of the key (LIMIT 1 subquery) | ok | key index index-only | Dependent subquery per candidate. |
| ods-07-window | Q-indep/oldest_due_strict/win-min | ROW_NUMBER heads of all incomplete rows, MIN of due eligible heads | ok | full incomplete scan + window sort | Linear in incomplete set; control form. |
| ods-08-min-of-limit | Q-pgx/oldest_due_strict/ods_min_of_limit | MIN over derived (NOT EXISTS ... ORDER BY next_retry_at LIMIT :limit) | ok | status_completed_retry + key index | Keeps one-row shape with early stop. Equal for :limit >= 1. |

## oldest_due_nonstrict

| merged id | origin(s) | idea | class | access path needed | notes |
|---|---|---|---|---|---|
| odn-00-base | catalog mdb-opt; = Q-indep/oldest_due_nonstrict/min-agg, Q-pgx/oldest_due_nonstrict/odn_min | Branch SQL | ok | per branch DDL | Branch SQL (also = Q-indep min-agg, Q-pgx odn_min). One row, NULL when none. |
| odn-00-main | catalog mdb-main | Main branch SQL (header from main catalog) | ok | main DDL indexes | Reference only; shape/order may differ from branch (e.g. GROUP BY/MIN, no completed_at). |
| odn-01-order | Q-indep/oldest_due_nonstrict/ord-limit, Q-pgx/oldest_due_nonstrict/odn_order | ORDER BY next_retry_at LIMIT :limit | ok | status_completed_retry; early stop at first owned-partition row | Zero rows when none (vs NULL row for base MIN); :limit must be 1 for equality. |
| odn-02-min-of-limit | Q-indep/oldest_due_nonstrict/min-over-limited, Q-pgx/oldest_due_nonstrict/odn_min_of_limit | MIN over derived ORDER BY next_retry_at LIMIT :limit | ok | status_completed_retry | One row, NULL when none; equal for :limit >= 1. |
| odn-03-order-force | Q-indep/oldest_due_nonstrict/ord-limit-force-idx3, Q-pgx/oldest_due_nonstrict/odn_order_force | 01-order + FORCE INDEX (idx_outbox_record_status_completed_retry) | hint | status_completed_retry | Error 1176 if index missing. |
| odn-04-min-force | Q-indep/oldest_due_nonstrict/min-force-idx3, Q-pgx/oldest_due_nonstrict/odn_min_force | Base MIN + FORCE INDEX status_completed_retry | hint | status_completed_retry | Error 1176 if index missing. |

## Invalid forms

None.


## User idea E5 — no `completed_at IS NULL` on the outer row (added 2026-11-05 by the orchestrator)

All five repository calls bind `status = NEW` (`JdbcOutboxRecordRepository` at 2a2663de), so the outer `completed_at IS NULL` is redundant
under the invariant `completed_at IS NULL <=> status IN (NEW, FAILED)` (not schema-enforced). The strict probes keep
`earlier.completed_at IS NULL`: it is the incompleteness test there (no status condition) and the seek column of the key index.

| merged id | idea | class | access path needed |
|---|---|---|---|
| ks-10-no-outer-completed | branch SQL minus outer `completed_at IS NULL` | status-conditional | `(status, created_at, id, partition_no, next_retry_at, record_key)` ordered; key index probes |
| kn-12-no-outer-completed | branch SQL minus outer `completed_at IS NULL` | status-conditional | as ks-10 |
| kn-13-no-completed | also minus inner `completed_at IS NULL` (control) | status-conditional | key index without completed_at equality: reads COMPLETED rows of the key |
| oc-07-no-completed-min | MIN form | status-conditional | full status range (no MIN shortcut: partition filter after created_at) |
| oc-08-no-completed-order | ORDER BY created_at, id LIMIT 1 | status-conditional | ordered scan, stops at first owned partition |
| ods-09-no-outer-completed | branch SQL minus outer `completed_at IS NULL` | status-conditional | `(status, next_retry_at, partition_no)` ordered; key index probes |
| odn-05-no-completed-min | MIN form | status-conditional | full due range |
| odn-06-no-completed-order | ORDER BY next_retry_at LIMIT 1 | status-conditional | ordered scan, stops at first owned partition |
| ks-11-head-no-outer-completed | ks-05-head minus outer `completed_at IS NULL` (user idea, 2026-11-06) | status-conditional | `(status, created_at, id, partition_no, next_retry_at, record_key)` ordered; key index head probe |
| kn-14-head-no-outer-completed | kn-05-head minus outer `completed_at IS NULL` (user idea, 2026-11-06) | status-conditional | as ks-11; head probe with status/due lookup |
