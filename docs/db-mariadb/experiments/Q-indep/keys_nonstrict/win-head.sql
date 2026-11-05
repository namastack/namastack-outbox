-- id: keys_nonstrict
-- group: HOT
-- kind: read
-- maxrows: batchSize
-- source: MariaDbJdbcOutboxRecordQueries.findNonStrictRecordKeys (query-package, mdb-opt)
-- note: form win-head
SELECT h.record_key FROM (SELECT r.record_key, r.partition_no, r.created_at, r.id, ROW_NUMBER() OVER (PARTITION BY r.record_key ORDER BY r.created_at, r.id) AS rn FROM outbox_record r WHERE r.status = :status AND r.completed_at IS NULL AND r.next_retry_at <= :now) h WHERE h.rn = 1 AND h.partition_no IN (:partitions) ORDER BY h.created_at, h.id LIMIT :limit
