-- id: keys_strict
-- group: HOT
-- kind: read
-- maxrows: batchSize
-- source: MariaDbJdbcOutboxRecordQueries.findStrictRecordKeys (query-package, mdb-opt)
-- note: form ks_tuple (proposer pgx)
SELECT o.record_key FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND o.completed_at IS NULL AND NOT EXISTS ( SELECT 1 FROM outbox_record earlier WHERE earlier.record_key = o.record_key AND earlier.completed_at IS NULL AND (earlier.created_at, earlier.id) < (o.created_at, o.id) ) ORDER BY o.created_at, o.id LIMIT :limit
