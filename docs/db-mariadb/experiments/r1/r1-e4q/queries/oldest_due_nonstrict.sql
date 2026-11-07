-- id: oldest_due_nonstrict
-- group: MON
-- kind: read
-- maxrows: one
-- source: MariaDbJdbcOutboxRecordQueries.oldestDueNonStrict (query-package, mdb-opt)
-- origin: Q-indep/oldest_due_nonstrict/ord-limit, Q-pgx/oldest_due_nonstrict/odn_order
-- class: ok
SELECT o.next_retry_at FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND o.completed_at IS NULL ORDER BY o.next_retry_at LIMIT :limit
