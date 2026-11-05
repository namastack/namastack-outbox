-- id: keys_nonstrict
-- group: HOT
-- kind: read
-- maxrows: batchSize
-- source: MariaDbJdbcOutboxRecordQueries.findNonStrictRecordKeys (query-package, mdb-opt)
-- note: form group-min
SELECT o.record_key FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND o.completed_at IS NULL GROUP BY o.record_key ORDER BY MIN(o.created_at), MIN(o.id) LIMIT :limit
