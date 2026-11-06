-- id: keys_strict
-- group: HOT
-- kind: read
-- maxrows: batchSize
-- source: QM-keys_strict-2 (from QI-keys_strict-2, QP-keys_strict-2)
SELECT o.record_key FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND o.completed_at IS NULL AND NOT EXISTS (SELECT 1 FROM outbox_record e WHERE e.record_key = o.record_key AND e.completed_at IS NULL AND (e.created_at, e.id) < (o.created_at, o.id)) ORDER BY o.created_at, o.id LIMIT :limit
