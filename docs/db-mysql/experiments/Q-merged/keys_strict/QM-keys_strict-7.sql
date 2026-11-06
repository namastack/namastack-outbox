-- id: keys_strict
-- group: HOT
-- kind: read
-- maxrows: batchSize
-- source: QM-keys_strict-7 (from QI-keys_strict-7)
SELECT o.record_key FROM outbox_record o FORCE INDEX (idx_outbox_record_status_completed_created_id) WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND o.completed_at IS NULL AND NOT EXISTS (SELECT 1 FROM outbox_record e FORCE INDEX (idx_outbox_record_key_completed_created_id) WHERE e.record_key = o.record_key AND e.completed_at IS NULL AND e.created_at <= o.created_at AND (e.created_at < o.created_at OR (e.created_at = o.created_at AND e.id < o.id))) ORDER BY o.created_at, o.id LIMIT :limit
