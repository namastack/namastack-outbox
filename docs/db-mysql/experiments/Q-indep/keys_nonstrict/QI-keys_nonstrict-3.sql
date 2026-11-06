-- id: keys_nonstrict
-- group: HOT
-- kind: read
-- maxrows: batchSize
-- source: QI-keys_nonstrict-3
SELECT o.record_key FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND o.completed_at IS NULL AND o.id = (SELECT e.id FROM outbox_record e WHERE e.record_key = o.record_key AND e.completed_at IS NULL AND e.status = :status AND e.next_retry_at <= :now ORDER BY e.created_at, e.id LIMIT 1) ORDER BY o.created_at, o.id LIMIT :limit
