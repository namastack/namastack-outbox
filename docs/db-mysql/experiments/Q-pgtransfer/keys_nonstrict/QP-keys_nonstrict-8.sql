-- id: keys_nonstrict
-- group: HOT
-- kind: read
-- maxrows: batchSize
-- source: QP-keys_nonstrict-8
SELECT t.record_key FROM ( SELECT o.record_key, o.created_at, o.id, ROW_NUMBER() OVER (PARTITION BY o.record_key ORDER BY o.created_at, o.id) AS rn FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND o.completed_at IS NULL ) t WHERE t.rn = 1 ORDER BY t.created_at, t.id LIMIT :limit
