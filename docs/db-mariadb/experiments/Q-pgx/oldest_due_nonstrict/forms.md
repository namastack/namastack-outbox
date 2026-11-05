# oldest_due_nonstrict forms (pgx)
PG hypothesis transferred: the Postgres design relies on a partial index (`WHERE completed_at IS NULL`), INCLUDE columns, LATERAL probes and plan-cache tricks. On InnoDB/MariaDB there are no partial indexes (COMPLETED rows are in every index; `completed_at IS NULL` is a `ref_or_null`/range on the 2nd key part), no INCLUDE (covering = trailing key columns), the table is clustered on `id` (VARCHAR(255)) and every secondary entry carries it, there is no LATERAL, no extended statistics and no generic-plan flips. Portable lessons kept: no GROUP BY when an anti-join already yields one row per key, order by index order with LIMIT, `<=` conjunct before the tie-break, redundant `completed_at IS NULL`. Smoke test: all forms parse on MariaDB 12 and return the same rows as the branch SQL on a 30k-row toy table (not a performance statement).

Index names refer to the branch DDL (`idx_outbox_record_status_completed_created_id (status, completed_at, created_at, id, partition_no, next_retry_at, record_key)`, `..._key_completed_created_id (record_key, completed_at, created_at, id)`, `..._status_completed_retry (status, completed_at, next_retry_at, partition_no)`, `..._status_completed_partition (status, completed_at, partition_no)`). `FORCE/IGNORE INDEX` forms fail with error 1176 on any schema lacking the named index: HIGH RISK, measurement aid only. Access path: status_completed_retry (status, completed_at, next_retry_at, partition_no): range on next_retry_at <= :now (toy EXPLAIN: range, Using index). PG kept MIN because ORDER BY had extra planning cost (plan-cache); MariaDB has no generic plans, so the PG reason does not transfer, but the early-stop argument of oldest_created does: MIN with a later-part filter (partition_no) may scan the whole due range, ORDER BY LIMIT stops at first row passing partition filter. Row shape: MIN one row (maybe NULL), ORDER BY zero rows if none.

| form | idea | risks | MySQL |
|---|---|---|---|
| odn_min | MIN(next_retry_at) (= branch) | no early stop (hypothesis) | yes |
| odn_order | ORDER BY next_retry_at LIMIT :limit | zero rows when none | yes |
| odn_min_of_limit | MIN over derived LIMIT | derived overhead | yes |
| odn_order_force | odn_order + FORCE INDEX status_completed_retry | HIGH RISK | yes |
| odn_min_force | odn_min + FORCE INDEX | HIGH RISK | yes |
