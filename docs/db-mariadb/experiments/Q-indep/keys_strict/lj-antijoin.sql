-- id: keys_strict
-- group: HOT
-- kind: read
-- maxrows: batchSize
-- source: MariaDbJdbcOutboxRecordQueries.findStrictRecordKeys (query-package, mdb-opt)
-- note: form lj-antijoin
SELECT o.record_key FROM outbox_record o LEFT JOIN outbox_record earlier ON earlier.record_key = o.record_key AND earlier.completed_at IS NULL AND earlier.created_at <= o.created_at AND (earlier.created_at < o.created_at OR (earlier.created_at = o.created_at AND earlier.id < o.id)) WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND o.completed_at IS NULL AND earlier.id IS NULL ORDER BY o.created_at, o.id LIMIT :limit
