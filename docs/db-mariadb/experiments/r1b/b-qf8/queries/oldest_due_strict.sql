-- id: oldest_due_strict
-- group: MON
-- kind: read
-- maxrows: one
-- source: MariaDbJdbcOutboxRecordQueries.oldestDueStrict (query-package, mdb-opt)
-- origin: Q-pgx/oldest_due_strict/ods_min_of_limit
-- class: ok
SELECT MIN(t.next_retry_at) FROM ( SELECT o.next_retry_at FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND o.completed_at IS NULL AND NOT EXISTS ( SELECT 1 FROM outbox_record earlier WHERE earlier.record_key = o.record_key AND earlier.completed_at IS NULL AND earlier.created_at <= o.created_at AND ( earlier.created_at < o.created_at OR (earlier.created_at = o.created_at AND earlier.id < o.id) ) ) ORDER BY o.next_retry_at LIMIT :limit ) t
