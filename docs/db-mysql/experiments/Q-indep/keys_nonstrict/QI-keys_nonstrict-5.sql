-- id: keys_nonstrict
-- group: HOT
-- kind: read
-- maxrows: batchSize
-- source: QI-keys_nonstrict-5
SELECT o.record_key FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND o.completed_at IS NULL GROUP BY o.record_key ORDER BY MIN(o.created_at), MIN(o.id) LIMIT :limit
