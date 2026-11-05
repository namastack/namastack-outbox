-- id: keys_strict
-- group: HOT
-- kind: read
-- maxrows: batchSize
-- source: MariaDbJdbcOutboxRecordQueries.findStrictRecordKeys (query-package, mdb-opt)
-- note: form win-head
SELECT h.record_key FROM (SELECT r.record_key, r.status, r.partition_no, r.next_retry_at, r.created_at, r.id, ROW_NUMBER() OVER (PARTITION BY r.record_key ORDER BY r.created_at, r.id) AS rn FROM outbox_record r WHERE r.completed_at IS NULL) h WHERE h.rn = 1 AND h.status = :status AND h.partition_no IN (:partitions) AND h.next_retry_at <= :now ORDER BY h.created_at, h.id LIMIT :limit
