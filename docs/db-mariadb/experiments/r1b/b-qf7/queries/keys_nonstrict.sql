-- id: keys_nonstrict
-- group: HOT
-- kind: read
-- maxrows: batchSize
-- source: MariaDbJdbcOutboxRecordQueries.findNonStrictRecordKeys (query-package, mdb-opt)
-- origin: Q-pgx/keys_nonstrict/kn_window
-- class: ok
SELECT t.record_key FROM ( SELECT o.record_key, o.created_at, o.id, ROW_NUMBER() OVER (PARTITION BY o.record_key ORDER BY o.created_at, o.id) AS rn FROM outbox_record o WHERE o.completed_at IS NULL AND o.status = :status AND o.next_retry_at <= :now AND o.partition_no IN (:partitions) ) t WHERE t.rn = 1 ORDER BY t.created_at, t.id LIMIT :limit
