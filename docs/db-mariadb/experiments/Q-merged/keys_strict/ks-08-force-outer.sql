-- id: keys_strict
-- group: HOT
-- kind: read
-- maxrows: batchSize
-- source: MariaDbJdbcOutboxRecordQueries.findStrictRecordKeys (query-package, mdb-opt)
-- origin: Q-indep/keys_strict/ne-split-force-idx1
-- class: hint
SELECT o.record_key FROM outbox_record o FORCE INDEX (idx_outbox_record_status_completed_created_id) WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND o.completed_at IS NULL AND NOT EXISTS (SELECT 1 FROM outbox_record earlier WHERE earlier.record_key = o.record_key AND earlier.completed_at IS NULL AND earlier.created_at < o.created_at) AND NOT EXISTS (SELECT 1 FROM outbox_record earlier WHERE earlier.record_key = o.record_key AND earlier.completed_at IS NULL AND earlier.created_at = o.created_at AND earlier.id < o.id) ORDER BY o.created_at, o.id LIMIT :limit
