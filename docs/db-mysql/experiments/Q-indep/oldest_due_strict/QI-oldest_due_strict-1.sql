-- id: oldest_due_strict
-- group: MON
-- kind: read
-- maxrows: one
-- source: QI-oldest_due_strict-1
SELECT MIN(o.next_retry_at) FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND o.completed_at IS NULL AND NOT EXISTS (SELECT 1 FROM outbox_record e WHERE e.record_key = o.record_key AND e.completed_at IS NULL AND (e.created_at, e.id) < (o.created_at, o.id))
