# oldest_due_strict forms (pgx)
PG hypothesis transferred: the Postgres design relies on a partial index (`WHERE completed_at IS NULL`), INCLUDE columns, LATERAL probes and plan-cache tricks. On InnoDB/MariaDB there are no partial indexes (COMPLETED rows are in every index; `completed_at IS NULL` is a `ref_or_null`/range on the 2nd key part), no INCLUDE (covering = trailing key columns), the table is clustered on `id` (VARCHAR(255)) and every secondary entry carries it, there is no LATERAL, no extended statistics and no generic-plan flips. Portable lessons kept: no GROUP BY when an anti-join already yields one row per key, order by index order with LIMIT, `<=` conjunct before the tie-break, redundant `completed_at IS NULL`. Smoke test: all forms parse on MariaDB 12 and return the same rows as the branch SQL on a 30k-row toy table (not a performance statement).

Index names refer to the branch DDL (`idx_outbox_record_status_completed_created_id (status, completed_at, created_at, id, partition_no, next_retry_at, record_key)`, `..._key_completed_created_id (record_key, completed_at, created_at, id)`, `..._status_completed_retry (status, completed_at, next_retry_at, partition_no)`, `..._status_completed_partition (status, completed_at, partition_no)`). `FORCE/IGNORE INDEX` forms fail with error 1176 on any schema lacking the named index: HIGH RISK, measurement aid only. Outer ordered by `next_retry_at` needs `..._status_completed_retry (status, completed_at, next_retry_at, partition_no)` (range `next_retry_at <= :now` in order, partition filter covered); predecessor probe needs the key index. PG: `MIN` forced every due row through the anti-join, `ORDER BY LIMIT 1` stops at the first passing row. MariaDB: the dependent subquery is evaluated only for rows reaching it, so the same early-stop applies to ORDER BY LIMIT; MIN has to evaluate all due rows.

| form | idea | risks | MySQL |
|---|---|---|---|
| ods_min | MIN with NOT EXISTS (main shape + completed_at) | evaluates the predecessor probe for every due row (hypothesis: slow on S4/S5) | yes |
| ods_order | ORDER BY next_retry_at LIMIT :limit (= branch) | if the optimizer picks the created_at index instead, it sorts | yes |
| ods_leftjoin | LEFT JOIN ... IS NULL, ORDER BY next_retry_at LIMIT | join reorder breaks order | yes |
| ods_head | `o.id = first incomplete id of key` | dependent subquery per candidate | yes |
| ods_split | two NOT EXISTS ranges | extra probe | yes |
| ods_min_of_limit | MIN over derived ORDER BY ... LIMIT :limit | keeps MIN shape with early stop | yes |
