-- id: oldest_due_nonstrict
-- group: MON
-- kind: read
-- maxrows: one
-- source: MariaDbJdbcOutboxRecordQueries.oldestDueNonStrict (query-package, mdb-opt)
-- origin: Q-indep/oldest_due_nonstrict/min-over-limited, Q-pgx/oldest_due_nonstrict/odn_min_of_limit
-- class: ok
SELECT MIN(r.next_retry_at) FROM (SELECT o.next_retry_at FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND o.completed_at IS NULL ORDER BY o.next_retry_at LIMIT :limit) r
