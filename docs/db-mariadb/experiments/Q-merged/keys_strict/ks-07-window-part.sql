-- id: keys_strict
-- group: HOT
-- kind: read
-- maxrows: batchSize
-- source: MariaDbJdbcOutboxRecordQueries.findStrictRecordKeys (query-package, mdb-opt)
-- origin: Q-pgx/keys_strict/ks_window
-- class: ok
SELECT t.record_key FROM ( SELECT o.record_key, o.created_at, o.id, o.partition_no, o.status, o.next_retry_at, ROW_NUMBER() OVER (PARTITION BY o.record_key ORDER BY o.created_at, o.id) AS rn FROM outbox_record o WHERE o.completed_at IS NULL AND o.partition_no IN (:partitions) ) t WHERE t.rn = 1 AND t.status = :status AND t.next_retry_at <= :now ORDER BY t.created_at, t.id LIMIT :limit
